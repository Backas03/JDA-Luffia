package kr.kro.backas.music;

import com.sedmelluq.discord.lavaplayer.player.AudioPlayer;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;

public class MusicTrack {
    private final MusicPlayerClient client;
    private final AudioPlayer player;
    private final Queue<AudioTrack> trackQueue;

    private volatile RepeatMode repeatMode;

    public MusicTrack(MusicPlayerClient client, AudioPlayer player) {
        this.client = client;
        this.player = player;
        this.trackQueue = new ConcurrentLinkedQueue<>();
        this.repeatMode = RepeatMode.NO_REPEAT;
    }

    public String getRepeatModeName() {
        return repeatMode.getName();
    }

    public RepeatMode getRepeatMode() {
        return repeatMode;
    }

    public void setRepeatMode(RepeatMode mode) {
        this.repeatMode = mode;
    }

    public void reset() {
        client.dismissLyrics();
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
        client.dismissLyrics();
        player.stopTrack();
        if (requeueEnded && endedTrack != null) {
            if (repeatMode == RepeatMode.REPEAT_CURRENT) {
                announce(endedTrack, MusicEmbeds.play(endedTrack, client.getGuild()).build());
                player.playTrack(endedTrack);
                return;
            }
            if (repeatMode == RepeatMode.REPEAT_ALL) {
                trackQueue.add(endedTrack);
            }
        }
        AudioTrack nextTrack = trackQueue.poll();
        if (nextTrack == null) {
            if (client.getAutoplay().onQueueEmpty()) return;
            client.disconnectFromVoiceChannelAndResetTrack();
            return;
        }
        announce(nextTrack, MusicEmbeds.play(nextTrack, client.getGuild()).build());
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

    public synchronized int skip(int count) {
        AudioTrack current = player.getPlayingTrack();
        List<AudioTrack> dropped = new ArrayList<>();
        for (int i = 1; i < count; i++) {
            AudioTrack next = trackQueue.poll();
            if (next == null) break;
            dropped.add(next);
        }
        if (repeatMode == RepeatMode.REPEAT_ALL) {
            if (current != null) trackQueue.add(current.makeClone());
            trackQueue.addAll(dropped);
        }
        playNextTrack(null, false);
        return dropped.size() + (current == null ? 0 : 1);
    }

    public synchronized int shuffle() {
        List<AudioTrack> tracks = new ArrayList<>(trackQueue);
        Collections.shuffle(tracks);
        trackQueue.clear();
        trackQueue.addAll(tracks);
        return tracks.size();
    }

    public synchronized int prioritize(Set<AudioTrack> matched) {
        List<AudioTrack> front = new ArrayList<>();
        List<AudioTrack> rest = new ArrayList<>();
        for (AudioTrack track : trackQueue) {
            if (matched.contains(track)) front.add(track);
            else rest.add(track);
        }
        Collections.shuffle(front);
        trackQueue.clear();
        trackQueue.addAll(front);
        trackQueue.addAll(rest);
        return front.size();
    }

    public synchronized int remove(Set<AudioTrack> tracks) {
        int before = trackQueue.size();
        trackQueue.removeIf(tracks::contains);
        return before - trackQueue.size();
    }

    public synchronized int reorder(List<AudioTrack> order) {
        Set<AudioTrack> present = Collections.newSetFromMap(new IdentityHashMap<>());
        present.addAll(trackQueue);
        Set<AudioTrack> placed = Collections.newSetFromMap(new IdentityHashMap<>());
        List<AudioTrack> arranged = new ArrayList<>();
        for (AudioTrack track : order) {
            if (present.contains(track) && placed.add(track)) arranged.add(track);
        }
        int reordered = arranged.size();
        for (AudioTrack track : trackQueue) {
            if (placed.add(track)) arranged.add(track);
        }
        trackQueue.clear();
        trackQueue.addAll(arranged);
        return reordered;
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
