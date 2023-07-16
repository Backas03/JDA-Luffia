package kr.kro.backas.music;

import com.sedmelluq.discord.lavaplayer.container.MediaContainerRegistry;
import com.sedmelluq.discord.lavaplayer.player.AudioLoadResultHandler;
import com.sedmelluq.discord.lavaplayer.player.AudioPlayer;
import com.sedmelluq.discord.lavaplayer.player.AudioPlayerManager;
import com.sedmelluq.discord.lavaplayer.player.DefaultAudioPlayerManager;
import com.sedmelluq.discord.lavaplayer.source.bandcamp.BandcampAudioSourceManager;
import com.sedmelluq.discord.lavaplayer.source.beam.BeamAudioSourceManager;
import com.sedmelluq.discord.lavaplayer.source.getyarn.GetyarnAudioSourceManager;
import com.sedmelluq.discord.lavaplayer.source.http.HttpAudioSourceManager;
import com.sedmelluq.discord.lavaplayer.source.soundcloud.SoundCloudAudioSourceManager;
import com.sedmelluq.discord.lavaplayer.source.twitch.TwitchStreamAudioSourceManager;
import com.sedmelluq.discord.lavaplayer.source.vimeo.VimeoAudioSourceManager;
import com.sedmelluq.discord.lavaplayer.source.youtube.YoutubeAudioSourceManager;
import com.sedmelluq.discord.lavaplayer.tools.FriendlyException;
import com.sedmelluq.discord.lavaplayer.track.AudioPlaylist;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import com.sedmelluq.discord.lavaplayer.track.AudioTrackInfo;
import kr.kro.backas.Luffia;
import kr.kro.backas.Main;
import kr.kro.backas.music.service.youtube.YoutubeService;
import kr.kro.backas.util.DurationUtil;
import kr.kro.backas.util.MemberUtil;
import kr.kro.backas.util.StackTraceUtil;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Message;

import java.awt.*;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class MusicPlayerManager {


    private final AudioPlayerManager manager;
    private final AudioPlayer player;
    private final TrackScheduler trackScheduler;

    private final Map<Long, AudioPlaylist> queries;

    public MusicPlayerManager(Luffia luffia) {

        this.manager = new DefaultAudioPlayerManager();
        this.manager.registerSourceManager(new YoutubeAudioSourceManager());
        this.manager.registerSourceManager(SoundCloudAudioSourceManager.createDefault());
        this.manager.registerSourceManager(new BandcampAudioSourceManager());
        this.manager.registerSourceManager(new VimeoAudioSourceManager());
        this.manager.registerSourceManager(new TwitchStreamAudioSourceManager());
        this.manager.registerSourceManager(new BeamAudioSourceManager());
        this.manager.registerSourceManager(new GetyarnAudioSourceManager());
        this.manager.registerSourceManager(new HttpAudioSourceManager(MediaContainerRegistry.DEFAULT_REGISTRY));

        YoutubeAudioSourceManager youtubeAudioSourceManager = this.manager.source(YoutubeAudioSourceManager.class);
        // there is 100 videos per page and the maximum playlist size is 5000
        youtubeAudioSourceManager.setPlaylistPageCount(50);

        this.player = this.manager.createPlayer();
        this.trackScheduler = new TrackScheduler(this.player);
        player.addListener(this.trackScheduler);
        this.queries = new HashMap<>();
        Guild guild = luffia.getPublishedGuild();
        guild.getAudioManager().setSendingHandler(new AudioForwarder(this.player));
    }

    public Map<Long, AudioPlaylist> getQueries() {
        return queries;
    }

    public AudioPlaylist getQuery(long memberId) {
        return queries.get(memberId);
    }

    public TrackScheduler getTrackScheduler() {
        return trackScheduler;
    }

    public void enqueue(Message replyTo, AudioTrack track) {
        AudioTrackInfo info = track.getInfo();
        if (trackScheduler.enqueue(replyTo, track)) {
            reply(replyTo, info, "해당 음악이 " + trackScheduler.size() + "번째 대기열에 추가 되었습니다.");
        }
    }

    public AudioPlayer getPlayer() {
        return player;
    }

    public void reply(Message replyTo, AudioTrackInfo info, String description) {
        EmbedBuilder builder = new EmbedBuilder()
                .setColor(Color.decode("#5e71ef"))
                .setTitle(info.title, info.uri)
                .setThumbnail(YoutubeService.getThumbnailURL(info.uri))
                .setDescription(description)
                .setFooter(MemberUtil.getName(replyTo.getMember()))
                .addField(
                        "재생 시간",
                        DurationUtil.formatDuration((int) (info.length / 1000)),
                        false
                );
        replyTo.replyEmbeds(builder.build()).queue();
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
        }

        @Override
        public void trackLoaded(AudioTrack track) {
            AudioTrackInfo info = track.getInfo();
            trackScheduler.enqueue(replyTo, track);
            reply(replyTo, info, "해당 음악이 " + trackScheduler.size() + "번째 대기열에 추가 되었습니다.");
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

            queries.put(member.getIdLong(), playlist);
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

    private void retry(ResultHandler handler) {
        manager.loadItem(handler.identifier + handler.query, handler);
    }

    public void search(Member member, Message replyTo, String identifier, String query) {
        retry(new ResultHandler(member, replyTo, identifier, query));
    }
}
