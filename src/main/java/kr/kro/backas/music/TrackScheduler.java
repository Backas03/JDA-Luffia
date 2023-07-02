package kr.kro.backas.music;

import com.sedmelluq.discord.lavaplayer.player.AudioPlayer;
import com.sedmelluq.discord.lavaplayer.player.event.AudioEventAdapter;
import com.sedmelluq.discord.lavaplayer.tools.FriendlyException;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import com.sedmelluq.discord.lavaplayer.track.AudioTrackEndReason;
import com.sedmelluq.discord.lavaplayer.track.AudioTrackInfo;
import kr.kro.backas.Luffia;
import kr.kro.backas.Main;
import kr.kro.backas.util.DurationUtil;
import kr.kro.backas.util.StackTraceUtil;
import kr.kro.backas.util.UserUtil;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.channel.middleman.AudioChannel;
import net.dv8tion.jda.api.exceptions.InsufficientPermissionException;
import net.dv8tion.jda.api.managers.AudioManager;

import java.awt.*;
import java.util.LinkedList;

public class TrackScheduler extends AudioEventAdapter {

    private final AudioPlayer player;
    private final LinkedList<AudioTrack> queue;

    private int repeatMode = 0;

    private AudioTrack nowPlaying;

    public TrackScheduler(AudioPlayer player) {
        this.player = player;
        this.queue = new LinkedList<>();
    }

    public AudioTrack getNowPlaying() {
        return nowPlaying;
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

    public boolean enqueue(Message message, AudioTrack track) {
        TrackUserData data = new TrackUserData(message);
        track.setUserData(data);

        if (nowPlaying == null) {
            nowPlaying = track;
            playNow(track, false);
            return false;
        }
        queue.add(track);
        return true;
    }

    private void popAndPlay() {
        nowPlaying = pop();
        if (nowPlaying == null) {
            Main.getLuffia()
                    .getPublishedGuild()
                    .getAudioManager()
                    .closeAudioConnection();
            repeatMode = RepeatMode.NO_REPEAT;
            return;
        }
        playNow(nowPlaying, false);
    }

    public void dequeue(int order) {
        queue.remove(order);
    }

    public void skip() {
        AudioTrack current = player.getPlayingTrack();
        nowPlaying = pop();
        player.stopTrack();
        if (repeatMode == RepeatMode.REPEAT_CURRENT) {
            nowPlaying = current.makeClone();
            playNow(nowPlaying, true);
            return;
        }
        if (repeatMode == RepeatMode.REPEAT_ALL) {
            if (nowPlaying == null) {
                nowPlaying = current.makeClone();
            }
            playNow(nowPlaying, true);
            queue.add(current.makeClone());
            return;
        }
        if (nowPlaying == null) {
            Main.getLuffia()
                    .getPublishedGuild()
                    .getAudioManager()
                    .closeAudioConnection();
            repeatMode = RepeatMode.NO_REPEAT;
            return;
        }
        if (repeatMode == RepeatMode.NO_REPEAT) {
            playNow(nowPlaying, false);
            return;
        }
    }

    public void quit() {
        queue.clear();
        player.stopTrack();
        Main.getLuffia()
                .getPublishedGuild()
                .getAudioManager()
                .closeAudioConnection();
        repeatMode = RepeatMode.NO_REPEAT;
    }

    public LinkedList<AudioTrack> getQueue() {
        return queue;
    }

    public void pause() {
        player.setPaused(true);
    }

    public boolean resume() {
        boolean b = player.isPaused();
        player.setPaused(false);
        return b;
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

    public void join(Message message) {
        AudioManager manager = Main.getLuffia().getPublishedGuild().getAudioManager();
        Member member = message.getMember();
        AudioChannel channel;
        if (member == null || member.getVoiceState() == null || (channel = member.getVoiceState().getChannel()) == null) {
            message.reply("참여할 음성채팅방을 찾을 수 없습니다\n" +
                    "해당 음악을 요청한 맴버가 음성채팅방에 참여해야합니다.").queue();
            return;
        }
        try {
            manager.openAudioConnection(channel);
            message.reply("참여중인 음성 채팅방에 참여하였습니다.").queue();
        } catch (InsufficientPermissionException ignore) {
            message.reply("음성채팅방에 참여할 수 없습니다.").queue();
        }
    }

    @Override
    public void onTrackStart(AudioPlayer player, AudioTrack track) {

    }

    public void playNow(AudioTrack track, boolean silent) {
        TrackUserData data = track.getUserData(TrackUserData.class);
        Message message = data.message();
        if (message == null) {
            return;
        }

        AudioManager manager = Main.getLuffia().getPublishedGuild().getAudioManager();
        Member member = message.getMember();
        AudioTrackInfo info = track.getInfo();
        EmbedBuilder builder = new EmbedBuilder()
                .setColor(Color.decode("#5e71ef"))
                .setTitle(info.title, Main.getLuffia().getYoutubeService().getThumbnailURL(info.uri))
                .setFooter(UserUtil.getName(message.getMember()))
                .addField(
                        "재생 시간",
                        DurationUtil.formatDuration((int) (info.length / 1000)),
                        false
                );
        if (!manager.isConnected()) {
            AudioChannel channel;
            if (member == null || member.getVoiceState() == null || (channel = member.getVoiceState().getChannel()) == null) {
                builder.setDescription(
                        "참여할 음성채팅방을 찾을 수 없습니다\n" +
                        "해당 음악을 요청한 맴버가 음성채팅방에 참여해야합니다."
                );
                message.replyEmbeds(builder.build()).queue();
                return;
            }
            try {
                manager.openAudioConnection(channel);
            } catch (InsufficientPermissionException ignore) {
                builder.setDescription("음성채팅방에 참여할 수 없습니다.");
                message.replyEmbeds(builder.build()).queue();
                return;
            }
        }

        if (!silent) {
            Main.getLuffia()
                    .getMusicPlayerManager()
                    .reply(message, track.getInfo(), "음악을 재생합니다.");
        }
        player.stopTrack();
        player.playTrack(track);
    }

    @Override
    public void onTrackEnd(AudioPlayer player, AudioTrack track, AudioTrackEndReason endReason) {
        System.out.println(endReason.mayStartNext + " " + endReason.name());
        if (endReason == AudioTrackEndReason.FINISHED) {
            if (repeatMode == RepeatMode.NO_REPEAT) {
                popAndPlay();
                return;
            }
            if (repeatMode == RepeatMode.REPEAT_ALL) {
                AudioTrack t = pop();
                if (t == null) t = track.makeClone();
                playNow(t, true);
                queue.add(t);
                return;
            }
            if (repeatMode == RepeatMode.REPEAT_CURRENT) {
                playNow(track.makeClone(), true);
                return;
            }
            return;
        }
        TrackUserData data = track.getUserData(TrackUserData.class);
        Message message = data.message();
        AudioTrackInfo info = track.getInfo();
        EmbedBuilder builder = new EmbedBuilder()
                .setColor(Color.decode("#5e71ef"))
                .setTitle(info.title, Main.getLuffia().getYoutubeService().getThumbnailURL(info.uri))
                .setFooter(UserUtil.getName(message.getMember()))
                .addField(
                        "재생 시간",
                        DurationUtil.formatDuration((int) (info.length / 1000)),
                        false
                );
        if (endReason == AudioTrackEndReason.LOAD_FAILED) {
            builder.setDescription("데이터 로드에 실패했습니다. 다음 곡을 재생합니다.");
            message.replyEmbeds(builder.build()).queue();
            return;
        }
        // TODO: queue == 0 exit
        // endReason == FINISHED: A track finished or died by an exception (mayStartNext = true).
        // endReason == LOAD_FAILED: Loading of a track failed (mayStartNext = true).
        // endReason == STOPPED: The player was stopped.
        // endReason == REPLACED: Another track started playing while this had not finished
        // endReason == CLEANUP: Player hasn't been queried for a while, if you want you can put a
        //                       clone of this back to your queue
    }

    @Override
    public void onTrackException(AudioPlayer player, AudioTrack track, FriendlyException exception) {
        TrackUserData data = track.getUserData(TrackUserData.class);
        Message message = data.message();
        Member member = message.getMember();
        EmbedBuilder builder = new EmbedBuilder()
                .setColor(Color.decode("#f1554a"))
                .setTitle("데이터 로드에 실패했습니다")
                .setDescription("해당 메시지 링크와 함께 관리자에게 문의해주세요")
                .setFooter(UserUtil.getName(member))
                .addField(
                        exception.getClass().getName(),
                        StackTraceUtil.convertDiscord(exception),
                        false
                );
        message.replyEmbeds(builder.build()).queue();
        skip();
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