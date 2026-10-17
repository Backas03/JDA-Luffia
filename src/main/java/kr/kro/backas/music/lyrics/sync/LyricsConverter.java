package kr.kro.backas.music.lyrics.sync;

import kr.kro.backas.config.Config;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import kr.kro.backas.Luffia;
import kr.kro.backas.Main;
import kr.kro.backas.music.MusicPlayerClient;
import kr.kro.backas.music.lyrics.LyricLine;
import kr.kro.backas.music.lyrics.LyricsSources;
import kr.kro.backas.music.lyrics.Lyrics;
import kr.kro.backas.music.lyrics.TranslationJobs;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

public final class LyricsConverter {

    private static final Logger LOGGER = LoggerFactory.getLogger(LyricsConverter.class);
    static final int MAX_SECONDS = Config.get().asr().convert().maxSeconds();
    static final long FIRST_PASS_MS = Config.get().asr().convert().firstPassSeconds() * 1000L;
    static final long PASS_STEP_MS = Config.get().asr().convert().passStepSeconds() * 1000L;
    private static final long POLL_MS = 1_000;
    private static final long CAPTURE_START_TIMEOUT_S = 60;
    static final int MIN_SPOKEN_LETTERS = 20;
    static final double SPOKEN_SHARE = 0.8;
    static final int MAX_DETECT_PASSES = 3;
    private static final Map<String, CompletableFuture<Optional<Lyrics>>> IN_FLIGHT = new ConcurrentHashMap<>();

    private LyricsConverter() {
    }

    public static boolean isAvailable() {
        AsrClient asr = asr();
        return asr != null && asr.isAvailable();
    }

    @Nullable
    private static AsrClient asr() {
        Luffia luffia = Main.getLuffia();
        return luffia == null ? null : luffia.getMusicPlayerController().getAsrClient();
    }

    public static CompletableFuture<Optional<Lyrics>> convert(MusicPlayerClient client, AudioTrack track, Lyrics lyrics) {
        return convert(client, track, lyrics, null);
    }

    public static CompletableFuture<Optional<Lyrics>> convert(MusicPlayerClient client, AudioTrack track, Lyrics lyrics,
                                                              @Nullable Consumer<Lyrics> onProgress) {
        return convert(client, track, lyrics, onProgress, false);
    }

    public static CompletableFuture<Optional<Lyrics>> convert(MusicPlayerClient client, AudioTrack track, Lyrics lyrics,
                                                              @Nullable Consumer<Lyrics> onProgress, boolean fresh) {
        String identifier = track.getIdentifier();
        List<LyricLine> stored = fresh ? null : AiLyricsStore.defaultStore().get(identifier);
        if (stored != null) return CompletableFuture.completedFuture(Optional.of(lyrics.withAiTimestamps(stored)));
        AsrClient asr = asr();
        if (asr == null || !asr.isAvailable() || !lyrics.hasPlain()) return CompletableFuture.completedFuture(Optional.empty());
        CompletableFuture<Optional<Lyrics>> job = IN_FLIGHT.computeIfAbsent(identifier, key -> start(key, client, asr, track, lyrics, onProgress));
        job.whenComplete((result, error) -> IN_FLIGHT.remove(identifier, job));
        return job;
    }

    private static List<String> lyricLines(Lyrics lyrics) {
        return LyricsTimestamper.usableLines(Arrays.asList(lyrics.plain().split("\\r?\\n")));
    }

    @Nullable
    private static String hintOf(List<String> lines) {
        List<LyricLine> asLines = new ArrayList<>(lines.size());
        for (String line : lines) asLines.add(new LyricLine(0, line));
        return LyricsAligner.languageHint(asLines);
    }

    @Nullable
    static String spokenLanguage(List<AsrClient.Segment> segments) {
        int hangul = 0;
        int japanese = 0;
        for (AsrClient.Segment segment : segments) {
            String text = segment.text();
            if (!LyricsTranscriber.isLyric(text.strip())) continue;
            for (int i = 0; i < text.length(); ) {
                int cp = text.codePointAt(i);
                i += Character.charCount(cp);
                if ((cp >= 0xAC00 && cp <= 0xD7A3) || (cp >= 0x1100 && cp <= 0x11FF) || (cp >= 0x3130 && cp <= 0x318F)) hangul++;
                else if ((cp >= 0x3040 && cp <= 0x30FF) || (cp >= 0x4E00 && cp <= 0x9FFF)) japanese++;
            }
        }
        int total = hangul + japanese;
        if (total < MIN_SPOKEN_LETTERS) return null;
        if (hangul >= total * SPOKEN_SHARE) return "ko";
        if (japanese >= total * SPOKEN_SHARE) return "ja";
        return null;
    }

    static boolean mismatched(@Nullable String lyricsLanguage, @Nullable String spoken) {
        if (lyricsLanguage == null || spoken == null) return false;
        return ("ja".equals(lyricsLanguage) || "ko".equals(lyricsLanguage)) && !lyricsLanguage.equals(spoken);
    }

    private static Optional<Lyrics> transcribeInstead(MusicPlayerClient client, AudioTrack track) {
        if (!LyricsTranscriber.isEnabled()) return Optional.empty();
        try {
            Optional<Lyrics> transcribed = LyricsTranscriber.transcribe(client, track).join();
            if (transcribed.isPresent()) {
                Main.getLuffia().getMusicPlayerController().getLyricsSources()
                        .override(track, new Lyrics(track.getInfo().title, track.getInfo().author, null, List.of(), false));
            }
            return transcribed;
        } catch (CompletionException e) {
            LOGGER.debug("transcribing lyrics instead failed for {}", track.getInfo().title, e.getCause() == null ? e : e.getCause());
            return Optional.empty();
        }
    }

    private static CompletableFuture<Optional<Lyrics>> start(String identifier, MusicPlayerClient client, AsrClient asr,
                                                             AudioTrack track, Lyrics original, @Nullable Consumer<Lyrics> onProgress) {
        String title = track.getInfo().title;
        if (lyricLines(original).size() < LyricsTimestamper.MIN_MATCHED) return CompletableFuture.completedFuture(Optional.empty());
        long length = track.getInfo().length;
        int seconds = (int) (length <= 0 ? MAX_SECONDS : Math.min(MAX_SECONDS, length / 1000 + 2));
        long trackMs = length <= 0 ? seconds * 1000L : length;
        long started = System.currentTimeMillis();
        ScratchCapture.Progressive progressive = ScratchCapture.start(client.getAudioPlayerManager(), track, seconds);
        CompletableFuture<Optional<Lyrics>> job = CompletableFuture.supplyAsync(() -> {
            Lyrics lyrics = original;
            List<String> lines = lyricLines(lyrics);
            String hint = hintOf(lines);
            boolean detected = false;
            int detectPasses = 0;
            PcmCapture capture;
            try {
                capture = progressive.capture().get(CAPTURE_START_TIMEOUT_S, TimeUnit.SECONDS);
            } catch (ExecutionException | TimeoutException e) {
                LOGGER.debug("audio capture did not start for {}", title, e);
                capture = null;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return Optional.<Lyrics>empty();
            }
            if (capture == null) {
                LOGGER.info("could not start decoding audio to timestamp lyrics for {}", title);
                return Optional.<Lyrics>empty();
            }
            long lastPassMs = 0;
            int passes = 0;
            long asrMs = 0;
            Optional<LyricsTimestamper.Result> last = Optional.empty();
            while (true) {
                boolean finished = progressive.done().isDone();
                long have = capture.capturedMs();
                long needed = lastPassMs == 0 ? FIRST_PASS_MS : lastPassMs + PASS_STEP_MS;
                if (!finished && have < needed) {
                    try {
                        Thread.sleep(POLL_MS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return Optional.<Lyrics>empty();
                    }
                    continue;
                }
                byte[] wav;
                long audioMs;
                if (finished) {
                    ScratchCapture.Captured captured = progressive.done().getNow(null);
                    wav = captured == null ? null : captured.wav();
                    audioMs = captured == null ? 0 : captured.capturedMs();
                } else {
                    wav = capture.snapshot();
                    audioMs = have;
                }
                if (wav == null || audioMs < PcmCapture.MIN_SECONDS * 1000L) {
                    if (finished) break;
                    lastPassMs = have;
                    continue;
                }
                long asrStarted = System.currentTimeMillis();
                AsrClient.Transcript transcript;
                try {
                    transcript = asr.transcribeDetailed(wav, detected ? hint : null, null);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
                List<AsrClient.Word> words = transcript.words();
                asrMs += System.currentTimeMillis() - asrStarted;
                passes++;
                if (!detected) {
                    String spoken = spokenLanguage(transcript.segments());
                    detectPasses++;
                    if (spoken == null && !finished && detectPasses < MAX_DETECT_PASSES) {
                        lastPassMs = have;
                        continue;
                    }
                    detected = true;
                    if (mismatched(hint, spoken)) {
                        LOGGER.info("vocals of {} sound {} but its lyrics are {}, looking for lyrics that match", title, spoken, hint);
                        LyricsSources sources = Main.getLuffia().getMusicPlayerController().getLyricsSources();
                        Lyrics alternative = sources.alternative(track, lyrics, spoken);
                        if (alternative == null || (!alternative.hasSynced() && lyricLines(alternative).size() < LyricsTimestamper.MIN_MATCHED)) {
                            LOGGER.info("no {} lyrics found for {}, transcribing the vocals instead", spoken, title);
                            return transcribeInstead(client, track);
                        }
                        sources.override(track, alternative);
                        LOGGER.info("switched {} to {} lyrics \"{}\" by {}", title, spoken, alternative.trackName(), alternative.artistName());
                        if (alternative.hasSynced()) return Optional.of(alternative);
                        lyrics = alternative;
                        lines = lyricLines(lyrics);
                        hint = spoken;
                    }
                }
                Optional<LyricsTimestamper.Result> result = LyricsTimestamper.timestamp(lines, words, finished ? audioMs : trackMs, !finished);
                if (result.isPresent()) {
                    last = finished ? Optional.of(LyricsTimestamper.withRests(result.get(), VocalActivity.of(wav))) : result;
                    if (onProgress != null && !finished) onProgress.accept(lyrics.withAiTimestamps(result.get().lines()));
                }
                lastPassMs = have;
                if (finished) break;
            }
            long elapsed = System.currentTimeMillis() - started;
            String timing = passes + " pass(es), asr " + asrMs + "ms, " + elapsed + "ms total";
            if (last.isEmpty()) {
                LOGGER.info("could not timestamp lyrics for {} ({} lines; {})", title, lines.size(), timing);
                return Optional.<Lyrics>empty();
            }
            AiLyricsStore.defaultStore().put(identifier, last.get());
            LOGGER.info("timestamped lyrics for {}: {} of {} lines matched ({})", title, last.get().matched(), last.get().total(), timing);
            return Optional.of(lyrics.withAiTimestamps(last.get().lines()));
        }, TranslationJobs.EXECUTOR);
        job.whenComplete((result, error) -> {
            if (error != null) LOGGER.info("lyrics timestamping failed for {}: {}", title, error.toString());
        });
        return job;
    }
}
