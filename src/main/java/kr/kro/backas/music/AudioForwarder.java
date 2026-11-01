package kr.kro.backas.music;

import com.sedmelluq.discord.lavaplayer.player.AudioPlayer;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import com.sedmelluq.discord.lavaplayer.track.playback.AudioFrame;
import net.dv8tion.jda.api.audio.AudioSendHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;

public class AudioForwarder implements AudioSendHandler {
    private static final Logger LOGGER = LoggerFactory.getLogger(AudioForwarder.class);
    static final long LATE_SEND_NS = 40_000_000L;
    static final long REPORT_EVERY_MS = 60_000;

    private final MusicPlayerClient client;
    private AudioFrame lastFrame;
    private long lastCallAt;
    private long windowStartedAt;
    private int lateSends;
    private long worstGapNs;
    private int missingFrames;
    private AudioTrack streamingTrack;

    public AudioForwarder(MusicPlayerClient client) {
        this.client = client;
    }

    @Override
    public boolean canProvide() {
        AudioPlayer player = client.getAudioPlayer();
        lastFrame = player.provide();
        long now = System.nanoTime();
        AudioTrack track = player.getPlayingTrack();
        boolean playing = track != null && !player.isPaused();
        if (playing) {
            if (lastCallAt != 0) {
                long gap = now - lastCallAt;
                if (gap > LATE_SEND_NS) {
                    lateSends++;
                    worstGapNs = Math.max(worstGapNs, gap);
                }
            }
            if (lastFrame != null) streamingTrack = track;
            else if (track == streamingTrack) missingFrames++;
            lastCallAt = now;
            report(now);
        } else {
            lastCallAt = 0;
        }
        if (lastFrame != null) {
            client.updatePosition();
            return true;
        }
        return false;
    }

    private void report(long now) {
        if (windowStartedAt == 0) windowStartedAt = now;
        if (now - windowStartedAt < REPORT_EVERY_MS * 1_000_000L) return;
        if (lateSends > 0 || missingFrames > 0) {
            LOGGER.warn("audio hiccups in the last {}s: {} late send(s) (worst gap {}ms), {} missing frame(s)",
                    REPORT_EVERY_MS / 1000, lateSends, worstGapNs / 1_000_000, missingFrames);
        }
        windowStartedAt = now;
        lateSends = 0;
        worstGapNs = 0;
        missingFrames = 0;
    }

    @Override
    public ByteBuffer provide20MsAudio() {
        return ByteBuffer.wrap(lastFrame.getData());
    }

    @Override
    public boolean isOpus() {
        return true;
    }
}
