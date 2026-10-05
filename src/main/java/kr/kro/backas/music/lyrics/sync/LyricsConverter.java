package kr.kro.backas.music.lyrics.sync;

import kr.kro.backas.config.Config;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import kr.kro.backas.Luffia;
import kr.kro.backas.Main;
import kr.kro.backas.music.MusicPlayerClient;
import kr.kro.backas.music.lyrics.LyricLine;
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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

public final class LyricsConverter {

    private static final Logger LOGGER = LoggerFactory.getLogger(LyricsConverter.class);
    static final int MAX_SECONDS = Config.get().whisper().convert().maxSeconds();
    static final long FIRST_PASS_MS = Config.get().whisper().convert().firstPassSeconds() * 1000L;
    static final long PASS_STEP_MS = Config.get().whisper().convert().passStepSeconds() * 1000L;
    private static final long POLL_MS = 1_000;
    private static final long CAPTURE_START_TIMEOUT_S = 60;
    private static final Map<String, CompletableFuture<Optional<Lyrics>>> IN_FLIGHT = new ConcurrentHashMap<>();

    private LyricsConverter() {
    }

    public static boolean isAvailable() {
        WhisperClient whisper = whisper();
        return whisper != null && whisper.isAvailable();
    }

    @Nullable
    private static WhisperClient whisper() {
        Luffia luffia = Main.getLuffia();
        return luffia == null ? null : luffia.getMusicPlayerController().getWhisperClient();
    }

    public static CompletableFuture<Optional<Lyrics>> convert(MusicPlayerClient client, AudioTrack track, Lyrics lyrics) {
        return convert(client, track, lyrics, null);
    }

    public static CompletableFuture<Optional<Lyrics>> convert(MusicPlayerClient client, AudioTrack track, Lyrics lyrics,
                                                              @Nullable Consumer<Lyrics> onProgress) {
        String identifier = track.getIdentifier();
        List<LyricLine> stored = AiLyricsStore.defaultStore().get(identifier);
        if (stored != null) return CompletableFuture.completedFuture(Optional.of(lyrics.withAiTimestamps(stored)));
        WhisperClient whisper = whisper();
        if (whisper == null || !whisper.isAvailable() || !lyrics.hasPlain()) return CompletableFuture.completedFuture(Optional.empty());
        CompletableFuture<Optional<Lyrics>> job = IN_FLIGHT.computeIfAbsent(identifier, key -> start(key, client, whisper, track, lyrics, onProgress));
        job.whenComplete((result, error) -> IN_FLIGHT.remove(identifier, job));
        return job;
    }

    private static CompletableFuture<Optional<Lyrics>> start(String identifier, MusicPlayerClient client, WhisperClient whisper,
                                                             AudioTrack track, Lyrics lyrics, @Nullable Consumer<Lyrics> onProgress) {
        List<String> lines = LyricsTimestamper.usableLines(Arrays.asList(lyrics.plain().split("\\r?\\n")));
        String title = track.getInfo().title;
        if (lines.size() < LyricsTimestamper.MIN_MATCHED) return CompletableFuture.completedFuture(Optional.empty());
        List<LyricLine> asLines = new ArrayList<>(lines.size());
        for (String line : lines) asLines.add(new LyricLine(0, line));
        String hint = LyricsAligner.languageHint(asLines);
        long length = track.getInfo().length;
        int seconds = (int) (length <= 0 ? MAX_SECONDS : Math.min(MAX_SECONDS, length / 1000 + 2));
        long trackMs = length <= 0 ? seconds * 1000L : length;
        long started = System.currentTimeMillis();
        ScratchCapture.Progressive progressive = ScratchCapture.start(client.getAudioPlayerManager(), track, seconds);
        CompletableFuture<Optional<Lyrics>> job = CompletableFuture.supplyAsync(() -> {
            PcmCapture capture;
            try {
                capture = progressive.capture().get(CAPTURE_START_TIMEOUT_S, TimeUnit.SECONDS);
            } catch (ExecutionException | TimeoutException e) {
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
            long whisperMs = 0;
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
                long whisperStarted = System.currentTimeMillis();
                List<WhisperClient.Word> words;
                try {
                    words = whisper.transcribe(wav, hint);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
                whisperMs += System.currentTimeMillis() - whisperStarted;
                passes++;
                Optional<LyricsTimestamper.Result> result = LyricsTimestamper.timestamp(lines, words, finished ? audioMs : trackMs, !finished);
                if (result.isPresent()) {
                    last = result;
                    if (onProgress != null && !finished) onProgress.accept(lyrics.withAiTimestamps(result.get().lines()));
                }
                lastPassMs = have;
                if (finished) break;
            }
            long elapsed = System.currentTimeMillis() - started;
            String timing = passes + " pass(es), whisper " + whisperMs + "ms, " + elapsed + "ms total";
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
