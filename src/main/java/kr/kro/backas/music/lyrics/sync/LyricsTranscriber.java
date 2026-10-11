package kr.kro.backas.music.lyrics.sync;

import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import com.sedmelluq.discord.lavaplayer.track.AudioTrackInfo;
import kr.kro.backas.Luffia;
import kr.kro.backas.Main;
import kr.kro.backas.config.Config;
import kr.kro.backas.music.MusicPlayerClient;
import kr.kro.backas.music.cache.DiskCache;
import kr.kro.backas.music.lyrics.LyricLine;
import kr.kro.backas.music.lyrics.Lyrics;
import kr.kro.backas.music.lyrics.TranslationJobs;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

public final class LyricsTranscriber {

    public static final String NOTE = "AI로 생성된 가사입니다. 정확하지 않을 수 있습니다.";
    public record Stored(@Nullable List<AiLyricsStore.Line> lines, long at, int version) {
    }

    static final int VERSION = 1;
    static final long OUTRO_GAP_MS = 3_000;
    private static final Pattern BRACKETED = Pattern.compile("^[\\[(（【♪].*[\\])）】♪]$");
    private static final List<String> HALLUCINATIONS = List.of(
            "ご視聴ありがとう", "ご清聴ありがとう", "チャンネル登録", "高評価", "作詞", "作曲", "編曲", "字幕",
            "시청해 주셔서", "구독", "좋아요와", "자막",
            "thank you for watching", "thanks for watching", "subscribe", "subtitles by", "transcribed by");
    private static final Logger LOGGER = LoggerFactory.getLogger(LyricsTranscriber.class);
    private static final Map<String, CompletableFuture<Optional<Lyrics>>> IN_FLIGHT = new ConcurrentHashMap<>();

    private LyricsTranscriber() {
    }

    public static boolean isEnabled() {
        return Config.get().whisper().transcribe().enabled() && LyricsConverter.isAvailable();
    }

    @Nullable
    public static Lyrics cached(AudioTrack track) {
        Stored stored = DiskCache.defaultCache().read(path(track), Stored.class);
        if (stored == null || stored.version() != VERSION || stored.lines() == null || stored.lines().isEmpty()) return null;
        List<LyricLine> lines = new ArrayList<>();
        for (AiLyricsStore.Line line : stored.lines()) lines.add(new LyricLine(line.timeMs(), line.text()));
        return lyrics(track.getInfo(), lines);
    }

    public static CompletableFuture<Optional<Lyrics>> transcribe(MusicPlayerClient client, AudioTrack track) {
        Stored stored = DiskCache.defaultCache().read(path(track), Stored.class);
        if (stored != null && stored.version() == VERSION) return CompletableFuture.completedFuture(Optional.ofNullable(cached(track)));
        WhisperClient whisper = whisper();
        if (whisper == null || !whisper.isAvailable()) return CompletableFuture.completedFuture(Optional.empty());
        String identifier = track.getIdentifier();
        CompletableFuture<Optional<Lyrics>> job = IN_FLIGHT.computeIfAbsent(identifier, key -> start(client, whisper, track));
        job.whenComplete((result, error) -> IN_FLIGHT.remove(identifier, job));
        return job;
    }

    private static CompletableFuture<Optional<Lyrics>> start(MusicPlayerClient client, WhisperClient whisper, AudioTrack track) {
        AudioTrackInfo info = track.getInfo();
        long length = info.length;
        int seconds = (int) (length <= 0 ? LyricsConverter.MAX_SECONDS : Math.min(LyricsConverter.MAX_SECONDS, length / 1000 + 2));
        long started = System.currentTimeMillis();
        CompletableFuture<Optional<Lyrics>> job = ScratchCapture.capture(client.getAudioPlayerManager(), track, seconds)
                .thenApplyAsync(captured -> {
                    if (captured == null) {
                        LOGGER.info("not enough audio captured to transcribe lyrics for {}", info.title);
                        return Optional.<Lyrics>empty();
                    }
                    WhisperClient.Transcript transcript;
                    try {
                        transcript = whisper.transcribeDetailed(captured.wav(), languageHint(info));
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                    List<LyricLine> lines = lines(transcript, captured.capturedMs());
                    boolean usable = letters(lines) >= Config.get().whisper().transcribe().minLetters();
                    List<AiLyricsStore.Line> stored = new ArrayList<>();
                    for (LyricLine line : lines) stored.add(new AiLyricsStore.Line(line.timeMs(), line.text()));
                    DiskCache.defaultCache().write(path(track), new Stored(usable ? stored : null, System.currentTimeMillis(), VERSION));
                    LOGGER.info("transcribed lyrics for {}: {} line(s), {} letters, {} ({}ms)", info.title, lines.size(), letters(lines),
                            usable ? "shown" : "too little to show", System.currentTimeMillis() - started);
                    return usable ? Optional.of(lyrics(info, lines)) : Optional.<Lyrics>empty();
                }, TranslationJobs.EXECUTOR);
        job.whenComplete((result, error) -> {
            if (error != null) LOGGER.info("lyrics transcription failed for {}: {}", info.title, error.toString());
        });
        return job;
    }

    static List<LyricLine> lines(WhisperClient.Transcript transcript, long audioMs) {
        List<LyricLine> lines = new ArrayList<>();
        long lastEnd = 0;
        for (WhisperClient.Segment segment : transcript.segments()) {
            String text = segment.text().strip();
            if (!isLyric(text)) continue;
            long start = segment.startMs();
            for (WhisperClient.Word word : transcript.words()) {
                if (word.startMs() < segment.startMs()) continue;
                if (word.startMs() < segment.endMs()) start = Math.max(start, LyricsAligner.onsetMs(word));
                break;
            }
            long at = Math.max(0, start - LyricsTimestamper.LINE_LEAD_MS);
            if (!lines.isEmpty() && at <= lines.get(lines.size() - 1).timeMs()) at = lines.get(lines.size() - 1).timeMs() + 1;
            lines.add(new LyricLine(at, text));
            lastEnd = Math.max(lastEnd, segment.endMs());
        }
        if (!lines.isEmpty() && lastEnd + OUTRO_GAP_MS < audioMs) lines.add(new LyricLine(lastEnd, ""));
        return lines;
    }

    static boolean isLyric(String text) {
        if (LyricsAligner.normalize(text).isEmpty() || BRACKETED.matcher(text).matches()) return false;
        String lower = text.toLowerCase(Locale.ROOT);
        for (String phrase : HALLUCINATIONS) {
            if (lower.contains(phrase)) return false;
        }
        return true;
    }

    static int letters(List<LyricLine> lines) {
        int count = 0;
        for (LyricLine line : lines) count += LyricsAligner.normalize(line.text()).length();
        return count;
    }

    @Nullable
    static String languageHint(AudioTrackInfo info) {
        String text = info.title + " " + info.author;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c >= 0x3040 && c <= 0x30FF) return "ja";
        }
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c >= 0xAC00 && c <= 0xD7A3) return "ko";
        }
        return null;
    }

    private static Lyrics lyrics(AudioTrackInfo info, List<LyricLine> lines) {
        StringBuilder plain = new StringBuilder();
        for (LyricLine line : lines) {
            if (!line.text().isBlank()) plain.append(line.text()).append('\n');
        }
        return new Lyrics(info.title, info.author, plain.toString().strip(), lines, false, false, true);
    }

    private static String path(AudioTrack track) {
        return "ai-transcripts/" + DiskCache.safeName(track.getIdentifier()) + ".json";
    }

    @Nullable
    private static WhisperClient whisper() {
        Luffia luffia = Main.getLuffia();
        return luffia == null ? null : luffia.getMusicPlayerController().getWhisperClient();
    }
}
