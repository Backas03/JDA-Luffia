package kr.kro.backas.music.lyrics.sync;

import com.sedmelluq.discord.lavaplayer.filter.AudioFilter;
import com.sedmelluq.discord.lavaplayer.player.AudioPlayer;
import com.sedmelluq.discord.lavaplayer.player.AudioPlayerManager;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import com.sedmelluq.discord.lavaplayer.track.AudioTrackState;
import com.sedmelluq.discord.lavaplayer.track.playback.AudioFrame;
import kr.kro.backas.music.lyrics.TranslationJobs;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

public final class ScratchCapture {

    public record Captured(byte[] wav, long capturedMs) {
    }

    public record Progressive(CompletableFuture<PcmCapture> capture, CompletableFuture<Captured> done) {
    }

    private static final Logger LOGGER = LoggerFactory.getLogger(ScratchCapture.class);
    private static final Semaphore PERMITS = new Semaphore(2);
    private static final long POLL_MS = 500;
    private static final long MIN_DEADLINE_MS = 90_000;
    private static final long DEADLINE_BASE_MS = 30_000;
    private static final double DEADLINE_PER_SECOND_MS = 1_500;

    private ScratchCapture() {
    }

    public static CompletableFuture<Captured> capture(AudioPlayerManager manager, AudioTrack track, int seconds) {
        return CompletableFuture.supplyAsync(() -> decode(manager, track, seconds, null), TranslationJobs.EXECUTOR);
    }

    public static Progressive start(AudioPlayerManager manager, AudioTrack track, int seconds) {
        CompletableFuture<PcmCapture> capture = new CompletableFuture<>();
        CompletableFuture<Captured> done = CompletableFuture.supplyAsync(() -> decode(manager, track, seconds, capture), TranslationJobs.EXECUTOR);
        done.whenComplete((result, error) -> {
            if (!capture.isDone()) capture.complete(null);
        });
        return new Progressive(capture, done);
    }

    @Nullable
    static Captured decode(AudioPlayerManager manager, AudioTrack track, int seconds, @Nullable CompletableFuture<PcmCapture> onCapture) {
        AtomicReference<PcmCapture> holder = new AtomicReference<>();
        AudioPlayer player = manager.createPlayer();
        try {
            PERMITS.acquire();
            try {
                player.setFilterFactory((scratchTrack, format, output) -> {
                    PcmCapture capture = new PcmCapture(scratchTrack, format.sampleRate, seconds);
                    if (holder.compareAndSet(null, capture) && onCapture != null) onCapture.complete(capture);
                    PcmCapture current = holder.get();
                    return List.<AudioFilter>of(current.tap(output));
                });
                AudioTrack clone = track.makeClone();
                if (!player.startTrack(clone, false)) return null;
                long deadline = System.currentTimeMillis() + Math.max(MIN_DEADLINE_MS, DEADLINE_BASE_MS + (long) (seconds * DEADLINE_PER_SECOND_MS));
                while (System.currentTimeMillis() < deadline) {
                    PcmCapture capture = holder.get();
                    if (capture != null && capture.wav().isDone()) break;
                    AudioFrame frame;
                    try {
                        frame = player.provide(POLL_MS, TimeUnit.MILLISECONDS);
                    } catch (TimeoutException e) {
                        frame = null;
                    }
                    if (frame != null && frame.isTerminator()) break;
                    if (frame == null && (player.getPlayingTrack() == null || clone.getState() == AudioTrackState.FINISHED)) break;
                }
                PcmCapture capture = holder.get();
                if (capture == null) {
                    LOGGER.debug("scratch decode produced no audio for {}", track.getInfo().title);
                    return null;
                }
                capture.finish();
                byte[] wav = capture.wav().getNow(null);
                return wav == null ? null : new Captured(wav, capture.capturedMs());
            } finally {
                PERMITS.release();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } finally {
            player.destroy();
        }
    }
}
