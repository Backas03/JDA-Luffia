package kr.kro.backas.music;

import com.sedmelluq.discord.lavaplayer.player.AudioPlayer;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import net.dv8tion.jda.api.JDA;

import java.util.LinkedList;
import java.util.Queue;

public class MusicTrack {

    public static final long WAITING_TIME_TO_QUIT_AFTER_TRACK_END = 1000 * 60L; // 60 seconds

    private final MusicPlayerClient client;
    private final AudioPlayer player;
    private final Queue<AudioTrack> trackQueue;
    private final JDA musicBot;

    private int repeatMode;
    private Thread waitQuitingThread;

    public MusicTrack(MusicPlayerClient client, AudioPlayer player, JDA musicBot) {
        this.client = client;
        this.player = player;
        this.musicBot = musicBot;
        this.trackQueue = new LinkedList<>();
        this.repeatMode = RepeatMode.NO_REPEAT;
        this.waitQuitingThread = null;
    }

    public String getRepeatModeName() {
        return RepeatMode.getName(repeatMode);
    }

    public void setRepeatMode(int mode) {
        this.repeatMode = mode;
    }

    public void reset() {
        this.player.stopTrack();
        this.trackQueue.clear();
        this.repeatMode = RepeatMode.NO_REPEAT;
        if (this.waitQuitingThread != null) {
            this.waitQuitingThread.interrupt();
        }
        this.waitQuitingThread = null;
    }

    // true: enqueue, false: play
    public boolean enqueueOrPlay(AudioTrack track) {
        if (waitQuitingThread != null) {
            waitQuitingThread.interrupt();
            waitQuitingThread = null;
        }
        if (hasPlayingTrack()) {
            trackQueue.add(track);
            return true;
        }
        player.playTrack(track);
        return false;
    }

    public void playNextTrack(AudioTrack endedTrack) {
        player.stopTrack();
        if (repeatMode == RepeatMode.REPEAT_CURRENT) {
            if (endedTrack == null) return;
            MusicSelection selection = endedTrack.getUserData(MusicSelection.class);
            selection.getQueryMessage()
                    .replyEmbeds(MusicTrackHandler.getPlayMessage(endedTrack, musicBot).build())
                    .mentionRepliedUser(false)
                    .queue();
            player.playTrack(endedTrack);
            return;
        }
        if (repeatMode == RepeatMode.REPEAT_ALL && endedTrack != null) {
            trackQueue.add(endedTrack); // 위에 적으면 queue 에 item 이 1개일때도 반복 재생 가능함
        }
        /* no repeat mode or repeat all mode */
        if (!hasNextTrack()) {
            client.disconnectToVoiceChannelAndResetTrack();
            return;
        }
        AudioTrack nextTrack = trackQueue.poll();
        MusicSelection selection = nextTrack.getUserData(MusicSelection.class);
        selection.getQueryMessage()
                .replyEmbeds(MusicTrackHandler.getPlayMessage(nextTrack, musicBot).build())
                .mentionRepliedUser(false)
                .queue();
        player.playTrack(nextTrack); // nextTrack cannot be null
    }

    private void startWaitQuitingThread() {
        if (waitQuitingThread != null) { // 중복 thread start 방지
            this.waitQuitingThread = new Thread(() -> {
                try {
                    Thread.sleep(WAITING_TIME_TO_QUIT_AFTER_TRACK_END);
                } catch (InterruptedException ignore) {
                    // do nothing
                    return;
                }
                if (hasPlayingTrack()) { // playing 중이라면 무시
                    return;
                }
                client.disconnectToVoiceChannelAndResetTrack();
            });
            this.waitQuitingThread.start();
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

    public Thread getWaitQuitingThread() {
        return waitQuitingThread;
    }
}
