package kr.kro.backas.music;

import com.sedmelluq.discord.lavaplayer.player.AudioPlayer;
import com.sedmelluq.discord.lavaplayer.player.event.AudioEventAdapter;
import com.sedmelluq.discord.lavaplayer.tools.FriendlyException;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import com.sedmelluq.discord.lavaplayer.track.AudioTrackEndReason;
import kr.kro.backas.Luffia;
import kr.kro.backas.Main;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.managers.AudioManager;

import java.util.LinkedList;

public class TrackScheduler extends AudioEventAdapter {

    private final AudioPlayer player;
    private final LinkedList<AudioTrack> queue;

    private int repeatMode = 0;

    public TrackScheduler(AudioPlayer player) {
        this.player = player;
        this.queue = new LinkedList<>();
    }

    public void setRepeatMode(int mode) {
        repeatMode = mode;
    }

    public int getRepeatMode() {
        return repeatMode;
    }

    public String getRepeatModeName() {
        return RepeatMode.getName(repeatMode);
    }

    public void enqueue(Member member, AudioTrack track) {
        TrackUserData data = new TrackUserData(member);
        track.setUserData(data);

        queue.add(track);
    }

    public void dequeue(int order) {
        queue.remove(order);
    }

    public void skip() {
        player.stopTrack();
    }

    public void pause() {

    }

    public void resume() {

    }

    public int size() {
        return queue.size();
    }

    public void setPosition(long ms) {
        player.getPlayingTrack().setPosition(ms);
    }

    public void setVolume(int volume) {
        player.setVolume(volume);
    }

    private AudioTrack pop() {
        if (queue.size() == 0) return null;
        return queue.remove(0);
    }

    @Override
    public void onTrackStart(AudioPlayer player, AudioTrack track) {
        TrackUserData data = track.getUserData(TrackUserData.class);
    }

    @Override
    public void onTrackEnd(AudioPlayer player, AudioTrack track, AudioTrackEndReason endReason) {
        if (endReason.mayStartNext) {
            // Start next track
            return;
        }

        // endReason == FINISHED: A track finished or died by an exception (mayStartNext = true).
        // endReason == LOAD_FAILED: Loading of a track failed (mayStartNext = true).
        // endReason == STOPPED: The player was stopped.
        // endReason == REPLACED: Another track started playing while this had not finished
        // endReason == CLEANUP: Player hasn't been queried for a while, if you want you can put a
        //                       clone of this back to your queue
    }

    @Override
    public void onTrackException(AudioPlayer player, AudioTrack track, FriendlyException exception) {
        // An already playing track threw an exception (track end event will still be received separately)
    }

    @Override
    public void onTrackStuck(AudioPlayer player, AudioTrack track, long thresholdMs) {
        // Audio track has been unable to provide us any audio, might want to just start a new track
    }

    public static final class RepeatMode {

        private RepeatMode() { }

        public static final int NO_REPEAT = 0;
        public static final int REPEAT_ALL = 1;
        public static final int REPEAT_CURRENT = 2;

        public static String getName(int mode) {
            if (REPEAT_ALL == mode) return "모든 트랙 반복";
            if (REPEAT_CURRENT == mode) return "현재 노래 반복";
            return "반복 없음";
        }
    }
}