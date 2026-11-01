package kr.kro.backas.music;

import com.sedmelluq.discord.lavaplayer.player.AudioPlayer;
import com.sedmelluq.discord.lavaplayer.player.event.AudioEventAdapter;
import com.sedmelluq.discord.lavaplayer.tools.FriendlyException;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import com.sedmelluq.discord.lavaplayer.track.AudioTrackEndReason;
import net.dv8tion.jda.api.entities.MessageEmbed;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class MusicTrackHandler extends AudioEventAdapter {
    private static final Logger LOGGER = LoggerFactory.getLogger(MusicTrackHandler.class);
    static final long MIN_PROGRESS_MS = 10_000;
    static final long END_MARGIN_MS = 5_000;

    private final MusicTrack musicTrack;
    private final MusicPlayerClient client;
    private volatile AudioTrack startedTrack;
    private volatile long startedAt;
    private volatile AudioTrack failedTrack;

    public MusicTrackHandler(MusicTrack musicTrack, MusicPlayerClient client) {
        this.musicTrack = musicTrack;
        this.client = client;
    }

    @Override
    public void onTrackStart(AudioPlayer player, AudioTrack track) {
        startedTrack = track;
        startedAt = track.getPosition();
    }

    @Override
    public void onTrackEnd(AudioPlayer player, AudioTrack track, AudioTrackEndReason endReason) {
        if (track == failedTrack) {
            failedTrack = null;
            if (endReason == AudioTrackEndReason.FINISHED) {
                resume(track);
                return;
            }
        }
        if (!endReason.mayStartNext) return;
        if (endReason == AudioTrackEndReason.LOAD_FAILED) {
            musicTrack.playNextTrack(null, false);
            return;
        }
        musicTrack.playNextTrack(track.makeClone());
    }

    @Override
    public void onTrackException(AudioPlayer player, AudioTrack track, FriendlyException exception) {
        LOGGER.warn("track exception. title={}, uri={}, severity={}, position={}ms",
                track.getInfo().title, track.getInfo().uri, exception.severity, track.getPosition(), exception);
        long from = track == startedTrack ? startedAt : 0;
        if (shouldResume(track.isSeekable(), track.getInfo().isStream, track.getDuration(), from, track.getPosition())) {
            failedTrack = track;
            return;
        }
        notify(track, MusicEmbeds.trackFailed(track, client.getGuild(), exception.getMessage()).build());
    }

    static boolean shouldResume(boolean seekable, boolean stream, long length, long startedAt, long position) {
        if (!seekable || stream) return false;
        if (position - startedAt < MIN_PROGRESS_MS) return false;
        return length <= 0 || length - position > END_MARGIN_MS;
    }

    private void resume(AudioTrack track) {
        long position = track.getPosition();
        AudioTrack resumed = track.makeClone();
        resumed.setPosition(position);
        LOGGER.warn("stream broke mid-track, resuming {} at {}ms", track.getInfo().title, position);
        client.resumeTrack(resumed);
    }

    @Override
    public void onTrackStuck(AudioPlayer player, AudioTrack track, long thresholdMs) {
        LOGGER.warn("track stuck for {}ms. title={}, uri={}", thresholdMs, track.getInfo().title, track.getInfo().uri);
        notify(track, MusicEmbeds.trackFailed(track, client.getGuild(), "스트리밍이 " + (thresholdMs / 1000) + "초 동안 멈춰 있습니다").build());
        musicTrack.playNextTrack(null, false);
    }

    private void notify(AudioTrack track, MessageEmbed embed) {
        MusicSelection selection = track.getUserData(MusicSelection.class);
        if (selection == null) return;
        try {
            selection.getSlashCommandInteractionEvent()
                    .getMessageChannel()
                    .sendMessageEmbeds(embed)
                    .queue(null, e -> LOGGER.debug("failed to notify track error", e));
        } catch (RuntimeException e) {
            LOGGER.debug("failed to notify track error", e);
        }
    }

    @Nullable
    static MusicSelection selectionOf(AudioTrack track) {
        return track.getUserData(MusicSelection.class);
    }
}
