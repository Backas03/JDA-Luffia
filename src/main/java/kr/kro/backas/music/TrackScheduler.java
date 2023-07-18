package kr.kro.backas.music;

import com.sedmelluq.discord.lavaplayer.player.AudioLoadResultHandler;
import com.sedmelluq.discord.lavaplayer.player.AudioPlayer;
import com.sedmelluq.discord.lavaplayer.player.event.AudioEventAdapter;
import com.sedmelluq.discord.lavaplayer.tools.FriendlyException;
import com.sedmelluq.discord.lavaplayer.track.AudioPlaylist;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import com.sedmelluq.discord.lavaplayer.track.AudioTrackEndReason;
import com.sedmelluq.discord.lavaplayer.track.AudioTrackInfo;
import kr.kro.backas.Main;
import kr.kro.backas.music.service.youtube.YoutubeService;
import kr.kro.backas.util.DurationUtil;
import kr.kro.backas.util.MemberUtil;
import kr.kro.backas.util.StackTraceUtil;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.channel.middleman.AudioChannel;
import net.dv8tion.jda.api.exceptions.InsufficientPermissionException;
import net.dv8tion.jda.api.managers.AudioManager;

import java.awt.*;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

public class TrackScheduler extends AudioEventAdapter {

    private final AudioPlayer player;
    private final LinkedList<AudioTrack> queue;

    private int repeatMode = 0;

    private AudioTrack nowPlaying;
    private final MusicPlayerManager manager;

    private final Map<Member, ResultHandler> handlers;

    public TrackScheduler(MusicPlayerManager manager, AudioPlayer player) {
        this.manager = manager;
        this.player = player;
        this.queue = new LinkedList<>();
        this.handlers = new HashMap<>();
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
        nowPlaying = null;
    }

    public void onQuit() {
        queue.clear();
        player.stopTrack();
        repeatMode = RepeatMode.NO_REPEAT;
        nowPlaying = null;
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
        TrackUserData data = track.getUserData(TrackUserData.class);
        handlers.remove(data.message().getMember());
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
                .setTitle(info.title, YoutubeService.getThumbnailURL(info.uri))
                .setFooter(MemberUtil.getName(message.getMember()))
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
        player.playTrack(nowPlaying = track.makeClone());
    }

    private void nextQueue(AudioTrackEndReason endReason, AudioTrack track) {
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
            }
        }
    }

    @Override
    public void onTrackEnd(AudioPlayer player, AudioTrack track, AudioTrackEndReason endReason) {
        if (endReason == AudioTrackEndReason.FINISHED) {
            nextQueue(endReason, track);
            return;
        }
        TrackUserData data = track.getUserData(TrackUserData.class);
        Message message = data.message();
        AudioTrackInfo info = track.getInfo();
        EmbedBuilder builder = new EmbedBuilder()
                .setColor(Color.decode("#5e71ef"))
                .setTitle(info.title, YoutubeService.getThumbnailURL(info.uri))
                .setFooter(MemberUtil.getName(message.getMember()))
                .addField(
                        "재생 시간",
                        DurationUtil.formatDuration((int) (info.length / 1000)),
                        false
                );
        if (endReason == AudioTrackEndReason.LOAD_FAILED) {
            builder.setDescription("데이터 로드에 실패했습니다. 다음 곡을 재생합니다.");
            message.replyEmbeds(builder.build()).queue();
            nextQueue(endReason, track);
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

        /*
        TrackUserData data = track.getUserData(TrackUserData.class);
        Message message = data.message();
        Member member = message.getMember();
        EmbedBuilder builder = new EmbedBuilder()
                .setColor(Color.decode("#f1554a"))
                .setTitle("데이터 로드에 실패했습니다")
                .setDescription("해당 메시지 링크와 함께 관리자에게 문의해주세요")
                .setFooter(MemberUtil.getName(member))
                .addField(
                        exception.getClass().getName(),
                        StackTraceUtil.convertDiscord(exception),
                        false
                );
        message.replyEmbeds(builder.build()).queue();
        skip();

         */
        TrackUserData data = track.getUserData(TrackUserData.class);
        Message message = data.message();
        ResultHandler handler = handlers.get(message.getMember());
        if (handler != null) {
            handler.loadFailed(exception);
        }
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

    private class ResultHandler implements AudioLoadResultHandler {

        private final Member member;
        private final Message replyTo;
        private final String identifier;
        private final String query;

        private int retryAttempt;

        private ResultHandler(Member member, Message replyTo, String identifier, String query) {
            this.retryAttempt = 0;
            this.member = member;
            this.replyTo = replyTo;
            this.identifier = identifier;
            this.query = query;
            handlers.put(member, this);
        }

        @Override
        public void trackLoaded(AudioTrack track) {
            AudioTrackInfo info = track.getInfo();
            enqueue(replyTo, track);
            manager.reply(replyTo, info, "해당 음악이 " + size() + "번째 대기열에 추가 되었습니다.");
        }

        @Override
        public void playlistLoaded(AudioPlaylist playlist) {
            List<AudioTrack> tracks = playlist.getTracks();

            EmbedBuilder builder = new EmbedBuilder()
                    .setColor(Color.decode("#ff8400"))
                    .setTitle("\"" + query + "\" 에 대한 검색 결과입니다")
                    .setDescription("아래는 " + tracks.size() + "개의 검색 항목 중 연관성이 가장 높은 5개의 곡입니다");
            int max = Math.min(5, tracks.size());
            for (int i=0; i<max; i++) {
                AudioTrack track = tracks.get(i);
                AudioTrackInfo info = track.getInfo();
                builder.addField(
                        (i + 1) + ". " + info.title + "\n (" + DurationUtil.formatDuration((int) (info.length / 1000)) + ")",
                        info.uri,
                        false
                );
            }
            builder.setFooter("1 ~ " + max + " 를 채팅창에 입력해주세요.");

            manager.getQueries().put(member.getIdLong(), playlist);
            replyTo.replyEmbeds(builder.build()).queue();
        }

        @Override
        public void noMatches() {
            EmbedBuilder builder = new EmbedBuilder()
                    .setColor(Color.decode("#f1554a"))
                    .setTitle("검색 데이터가 존재하지 않습니다")
                    .setDescription(query)
                    .setFooter(MemberUtil.getName(member));
            replyTo.replyEmbeds(builder.build()).queue();
        }

        @Override
        public void loadFailed(FriendlyException exception) {
            final int maxAttempt = 3; // max retry attempt
            if (++retryAttempt == maxAttempt) {
                EmbedBuilder builder = new EmbedBuilder()
                        .setColor(Color.decode("#f1554a"))
                        .setTitle("검색 데이터 로드에 실패했습니다")
                        .setDescription("해당 메시지 링크와 함께 관리자에게 문의해주세요")
                        .setFooter(MemberUtil.getName(member))
                        .addField(
                                exception.getClass().getName(),
                                StackTraceUtil.convertDiscord(exception),
                                false
                        );
                replyTo.replyEmbeds(builder.build()).queue();
                return;
            }
            EmbedBuilder builder = new EmbedBuilder()
                    .setColor(Color.decode("#f1554a"))
                    .setTitle("유튜브 서버와 통신에 실패했습니다.")
                    .addField("재통신을 시도합니다.", "재시도 횟수 " + retryAttempt + "/" + maxAttempt, false)
                    .setFooter(MemberUtil.getName(member));
            replyTo.replyEmbeds(builder.build()).queue();
            retry(this);
        }
    }

    protected void retry(ResultHandler handler) {
        manager.getPlayerManager().loadItem(handler.identifier + handler.query, handler);
    }

    public void search(Member member, Message replyTo, String identifier, String query) {
       retry(new TrackScheduler.ResultHandler(member, replyTo, identifier, query));
    }
}