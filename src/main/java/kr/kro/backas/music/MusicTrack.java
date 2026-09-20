package kr.kro.backas.music;

import com.sedmelluq.discord.lavaplayer.player.AudioPlayer;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import org.jetbrains.annotations.Nullable;

import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

public class MusicTrack {
    private final MusicPlayerClient client;
    private final AudioPlayer player;
    private final Queue<AudioTrack> trackQueue;
    private final JDA musicBot;

    private volatile RepeatMode repeatMode;

    public MusicTrack(MusicPlayerClient client, AudioPlayer player, JDA musicBot) {
        this.client = client;
        this.player = player;
        this.musicBot = musicBot;
        this.trackQueue = new ConcurrentLinkedQueue<>();
        this.repeatMode = RepeatMode.NO_REPEAT;
    }

    public String getRepeatModeName() {
        return repeatMode.getName();
    }

    public void setRepeatMode(RepeatMode mode) {
        this.repeatMode = mode;
    }

    public void reset() {
        client.disableAutoLyrics("재생을 중지했습니다");
        this.player.stopTrack();
        this.trackQueue.clear();
        this.repeatMode = RepeatMode.NO_REPEAT;
    }

    public synchronized boolean enqueueOrPlay(AudioTrack track) {
        if (hasPlayingTrack()) {
            trackQueue.add(track);
            return true;
        }
        player.playTrack(track);
        return false;
    }

    public void playNextTrack(@Nullable AudioTrack endedTrack) {
        playNextTrack(endedTrack, true);
    }

    public synchronized void playNextTrack(@Nullable AudioTrack endedTrack, boolean requeueEnded) {
        client.stopLyrics("곡이 바뀌었습니다");
        player.stopTrack();
        if (requeueEnded && endedTrack != null) {
            if (repeatMode == RepeatMode.REPEAT_CURRENT) {
                announce(endedTrack, MusicEmbeds.play(endedTrack, musicBot).build());
                player.playTrack(endedTrack);
                return;
            }
            if (repeatMode == RepeatMode.REPEAT_ALL) {
                trackQueue.add(endedTrack);
            }
        }
        AudioTrack nextTrack = trackQueue.poll();
        if (nextTrack == null) {
            client.disconnectFromVoiceChannelAndResetTrack();
            return;
        }
        announce(nextTrack, MusicEmbeds.play(nextTrack, musicBot).build());
        player.playTrack(nextTrack);
    }

    private void announce(AudioTrack track, MessageEmbed embed) {
        MusicSelection selection = track.getUserData(MusicSelection.class);
        if (selection == null) return;
        SlashCommandInteractionEvent event = selection.getSlashCommandInteractionEvent();
        if (event.isAcknowledged()) {
            event.getMessageChannel().sendMessageEmbeds(embed).queue();
        } else {
            event.replyEmbeds(embed).mentionRepliedUser(false).queue();
        }
    }

    public boolean hasNextTrack() {
        return !trackQueue.isEmpty();
    }

    public Queue<AudioTrack> getTrackQueue() {
        return trackQueue;
    }

    public boolean isNowPlaying() {
        return !player.isPaused() && hasPlayingTrack();
    }

    public boolean hasPlayingTrack() {
        return player.getPlayingTrack() != null;
    }
}
