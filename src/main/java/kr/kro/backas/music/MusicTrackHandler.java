package kr.kro.backas.music;

import com.sedmelluq.discord.lavaplayer.player.AudioPlayer;
import com.sedmelluq.discord.lavaplayer.player.event.AudioEventAdapter;
import com.sedmelluq.discord.lavaplayer.tools.FriendlyException;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import com.sedmelluq.discord.lavaplayer.track.AudioTrackEndReason;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.MessageEmbed;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class MusicTrackHandler extends AudioEventAdapter {
    private static final Logger LOGGER = LoggerFactory.getLogger(MusicTrackHandler.class);

    private final MusicTrack musicTrack;
    private final JDA musicBot;

    public MusicTrackHandler(MusicTrack musicTrack, JDA musicBot) {
        this.musicTrack = musicTrack;
        this.musicBot = musicBot;
    }

    @Deprecated
    public static EmbedBuilder getPlayMessage(AudioTrack track, JDA musicBot) {
        return MusicEmbeds.play(track, musicBot);
    }

    @Deprecated
    public static EmbedBuilder getEnqueueMessage(AudioTrack track, JDA musicBot, int position) {
        return MusicEmbeds.enqueue(track, musicBot, position);
    }

    @Override
    public void onTrackEnd(AudioPlayer player, AudioTrack track, AudioTrackEndReason endReason) {
        if (!endReason.mayStartNext) return;
        if (endReason == AudioTrackEndReason.LOAD_FAILED) {
            musicTrack.playNextTrack(null, false);
            return;
        }
        musicTrack.playNextTrack(track.makeClone());
    }

    @Override
    public void onTrackException(AudioPlayer player, AudioTrack track, FriendlyException exception) {
        LOGGER.warn("track exception. title={}, uri={}, severity={}",
                track.getInfo().title, track.getInfo().uri, exception.severity, exception);
        notify(track, MusicEmbeds.trackFailed(track, musicBot, exception.getMessage()).build());
    }

    @Override
    public void onTrackStuck(AudioPlayer player, AudioTrack track, long thresholdMs) {
        LOGGER.warn("track stuck for {}ms. title={}, uri={}", thresholdMs, track.getInfo().title, track.getInfo().uri);
        notify(track, MusicEmbeds.trackFailed(track, musicBot, "스트리밍이 " + (thresholdMs / 1000) + "초 동안 멈춰 있습니다").build());
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
