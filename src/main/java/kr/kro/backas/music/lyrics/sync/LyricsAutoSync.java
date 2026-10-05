package kr.kro.backas.music.lyrics.sync;

import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import kr.kro.backas.Luffia;
import kr.kro.backas.Main;
import kr.kro.backas.music.MusicPlayerClient;
import kr.kro.backas.music.lyrics.Lyrics;
import kr.kro.backas.music.lyrics.LyricsSession;
import kr.kro.backas.music.lyrics.TranslationJobs;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

public final class LyricsAutoSync {

    public static final int PRELOAD_COUNT = 2;
    static final int CAPTURE_SECONDS = PcmCapture.DEFAULT_SECONDS;

    private static final Logger LOGGER = LoggerFactory.getLogger(LyricsAutoSync.class);
    private static final Map<String, CompletableFuture<Optional<LyricsAligner.Alignment>>> IN_FLIGHT = new ConcurrentHashMap<>();

    private LyricsAutoSync() {
    }

    public static boolean isEnabled() {
        WhisperClient whisper = whisper();
        return whisper != null && whisper.isConfigured();
    }

    @Nullable
    private static WhisperClient whisper() {
        Luffia luffia = Main.getLuffia();
        return luffia == null ? null : luffia.getMusicPlayerController().getWhisperClient();
    }

    public static void apply(MusicPlayerClient client, AudioTrack track, Lyrics lyrics, LyricsSession session) {
        LyricsOffsets.Entry cached = LyricsOffsets.defaultStore().get(track.getIdentifier());
        if (cached != null) {
            session.setAutoOffsetMs(cached.offsetMs());
            return;
        }
        CompletableFuture<Optional<LyricsAligner.Alignment>> job = schedule(client, track, lyrics);
        if (job == null) return;
        job.thenAccept(result -> result.ifPresent(alignment -> {
            LyricsSession current = client.getLyricsSession();
            if (current != null && current.isForTrack(track)) current.setAutoOffsetMs(alignment.offsetMs());
        }));
    }

    public static void preload(MusicPlayerClient client, AudioTrack track, Lyrics lyrics) {
        if (LyricsOffsets.defaultStore().get(track.getIdentifier()) != null) return;
        schedule(client, track, lyrics);
    }

    @Nullable
    private static CompletableFuture<Optional<LyricsAligner.Alignment>> schedule(MusicPlayerClient client, AudioTrack track, Lyrics lyrics) {
        WhisperClient whisper = whisper();
        if (whisper == null || !whisper.isAvailable() || !lyrics.hasSynced()) return null;
        String identifier = track.getIdentifier();
        CompletableFuture<Optional<LyricsAligner.Alignment>> job =
                IN_FLIGHT.computeIfAbsent(identifier, key -> start(key, client, whisper, lyrics, track));
        job.whenComplete((result, error) -> IN_FLIGHT.remove(identifier, job));
        return job;
    }

    private static CompletableFuture<Optional<LyricsAligner.Alignment>> start(String identifier, MusicPlayerClient client, WhisperClient whisper,
                                                                              Lyrics lyrics, AudioTrack track) {
        LyricsOffsets store = LyricsOffsets.defaultStore();
        String hint = LyricsAligner.languageHint(lyrics.synced());
        String title = track.getInfo().title;
        long started = System.currentTimeMillis();
        CompletableFuture<Optional<LyricsAligner.Alignment>> job = ScratchCapture
                .capture(client.getAudioPlayerManager(), track, CAPTURE_SECONDS)
                .thenApplyAsync(captured -> {
                    if (captured == null) {
                        LOGGER.info("not enough audio captured to align lyrics for {}", title);
                        return Optional.<LyricsAligner.Alignment>empty();
                    }
                    List<WhisperClient.Word> words;
                    try {
                        words = whisper.transcribe(captured.wav(), hint);
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                    Optional<LyricsAligner.Alignment> result = LyricsAligner.align(words, lyrics.synced(), captured.capturedMs());
                    long elapsed = System.currentTimeMillis() - started;
                    if (result.isPresent()) {
                        LyricsAligner.Alignment alignment = result.get();
                        store.put(identifier, alignment.offsetMs(), LyricsOffsets.SOURCE_AUTO);
                        LOGGER.info("aligned lyrics for {}: offset {}ms from {} lines (spread {}ms, {}s audio, {}ms)",
                                title, alignment.offsetMs(), alignment.matchedLines(), alignment.spreadMs(),
                                captured.capturedMs() / 1000, elapsed);
                    } else {
                        store.put(identifier, 0, LyricsOffsets.SOURCE_NONE);
                        LOGGER.info("lyrics alignment found no reliable match for {} ({} words, {}ms)", title, words.size(), elapsed);
                    }
                    return result;
                }, TranslationJobs.EXECUTOR);
        job.whenComplete((result, error) -> {
            if (error != null) LOGGER.info("lyrics alignment failed for {}: {}", title, error.toString());
        });
        return job;
    }
}
