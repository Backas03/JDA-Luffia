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
        String identifier = track.getIdentifier();
        LyricsOffsets store = LyricsOffsets.defaultStore();
        LyricsOffsets.Entry cached = store.get(identifier);
        if (cached != null) {
            session.setAutoOffsetMs(cached.offsetMs());
            return;
        }
        PcmCapture capture = client.getCapture(track);
        WhisperClient whisper = whisper();
        if (capture == null || whisper == null || !whisper.isAvailable()) return;
        CompletableFuture<Optional<LyricsAligner.Alignment>> job =
                IN_FLIGHT.computeIfAbsent(identifier, key -> start(key, capture, whisper, lyrics, store, track));
        job.thenAccept(result -> result.ifPresent(alignment -> {
            LyricsSession current = client.getLyricsSession();
            if (current != null && current.isForTrack(track)) current.setAutoOffsetMs(alignment.offsetMs());
        }));
    }

    private static CompletableFuture<Optional<LyricsAligner.Alignment>> start(String identifier, PcmCapture capture, WhisperClient whisper,
                                                                              Lyrics lyrics, LyricsOffsets store, AudioTrack track) {
        String hint = LyricsAligner.languageHint(lyrics.synced());
        String title = track.getInfo().title;
        CompletableFuture<Optional<LyricsAligner.Alignment>> job = capture.wav().thenApplyAsync(wav -> {
            if (wav == null) {
                LOGGER.debug("not enough audio captured to align lyrics for {}", title);
                return Optional.<LyricsAligner.Alignment>empty();
            }
            List<WhisperClient.Word> words;
            try {
                words = whisper.transcribe(wav, hint);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            Optional<LyricsAligner.Alignment> result = LyricsAligner.align(words, lyrics.synced(), capture.capturedMs());
            if (result.isPresent()) {
                LyricsAligner.Alignment alignment = result.get();
                store.put(identifier, alignment.offsetMs(), LyricsOffsets.SOURCE_AUTO);
                LOGGER.info("aligned lyrics for {}: offset {}ms from {} lines (spread {}ms)",
                        title, alignment.offsetMs(), alignment.matchedLines(), alignment.spreadMs());
            } else {
                store.put(identifier, 0, LyricsOffsets.SOURCE_NONE);
                LOGGER.info("lyrics alignment found no reliable match for {} ({} words)", title, words.size());
            }
            return result;
        }, TranslationJobs.EXECUTOR);
        job.whenComplete((result, error) -> {
            IN_FLIGHT.remove(identifier);
            if (error != null) LOGGER.info("lyrics alignment failed for {}: {}", title, error.toString());
        });
        return job;
    }
}
