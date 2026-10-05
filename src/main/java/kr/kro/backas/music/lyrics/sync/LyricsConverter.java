package kr.kro.backas.music.lyrics.sync;

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

public final class LyricsConverter {

    private static final Logger LOGGER = LoggerFactory.getLogger(LyricsConverter.class);
    static final int MAX_SECONDS = 900;
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
        String identifier = track.getIdentifier();
        List<LyricLine> stored = AiLyricsStore.defaultStore().get(identifier);
        if (stored != null) return CompletableFuture.completedFuture(Optional.of(lyrics.withAiTimestamps(stored)));
        WhisperClient whisper = whisper();
        if (whisper == null || !whisper.isAvailable() || !lyrics.hasPlain()) return CompletableFuture.completedFuture(Optional.empty());
        CompletableFuture<Optional<Lyrics>> job = IN_FLIGHT.computeIfAbsent(identifier, key -> start(key, client, whisper, track, lyrics));
        job.whenComplete((result, error) -> IN_FLIGHT.remove(identifier, job));
        return job;
    }

    private static CompletableFuture<Optional<Lyrics>> start(String identifier, MusicPlayerClient client, WhisperClient whisper,
                                                             AudioTrack track, Lyrics lyrics) {
        List<String> lines = LyricsTimestamper.usableLines(Arrays.asList(lyrics.plain().split("\\r?\\n")));
        String title = track.getInfo().title;
        if (lines.size() < LyricsTimestamper.MIN_MATCHED) return CompletableFuture.completedFuture(Optional.empty());
        List<LyricLine> asLines = new ArrayList<>(lines.size());
        for (String line : lines) asLines.add(new LyricLine(0, line));
        String hint = LyricsAligner.languageHint(asLines);
        long length = track.getInfo().length;
        int seconds = (int) (length <= 0 ? MAX_SECONDS : Math.min(MAX_SECONDS, length / 1000 + 2));
        long started = System.currentTimeMillis();
        CompletableFuture<Optional<Lyrics>> job = ScratchCapture.capture(client.getAudioPlayerManager(), track, seconds)
                .thenApplyAsync(captured -> {
                    long decodedAt = System.currentTimeMillis();
                    if (captured == null) {
                        LOGGER.info("not enough audio captured to timestamp lyrics for {}", title);
                        return Optional.<Lyrics>empty();
                    }
                    List<WhisperClient.Word> words;
                    try {
                        words = whisper.transcribe(captured.wav(), hint);
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                    Optional<LyricsTimestamper.Result> result = LyricsTimestamper.timestamp(lines, words, captured.capturedMs());
                    long now = System.currentTimeMillis();
                    String timing = "decode " + (decodedAt - started) + "ms, whisper " + (now - decodedAt) + "ms, "
                            + captured.capturedMs() / 1000 + "s of " + seconds + "s audio";
                    if (result.isEmpty()) {
                        LOGGER.info("could not timestamp lyrics for {} ({} words, {} lines; {})", title, words.size(), lines.size(), timing);
                        return Optional.<Lyrics>empty();
                    }
                    AiLyricsStore.defaultStore().put(identifier, result.get());
                    LOGGER.info("timestamped lyrics for {}: {} of {} lines matched ({})",
                            title, result.get().matched(), result.get().total(), timing);
                    return Optional.of(lyrics.withAiTimestamps(result.get().lines()));
                }, TranslationJobs.EXECUTOR);
        job.whenComplete((result, error) -> {
            if (error != null) LOGGER.info("lyrics timestamping failed for {}: {}", title, error.toString());
        });
        return job;
    }
}
