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
import kr.kro.backas.util.DurationUtil;
import kr.kro.backas.util.StackTraceUtil;
import kr.kro.backas.util.UserUtil;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Message;

import java.awt.*;

public class MusicPlayerManager {

    private final JDA discordAPI;

    private final AudioPlayerManager manager;
    private final AudioPlayer player;
    private final TrackScheduler trackScheduler;

    public MusicPlayerManager(JDA discordAPI) {
        this.discordAPI = discordAPI;

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
    }

    public TrackScheduler getTrackScheduler() {
        return trackScheduler;
    }

    public void enqueue(Member member, Message replyTo, String identifier, String query) {
        manager.loadItem(identifier + query, new AudioLoadResultHandler() {
            @Override
            public void trackLoaded(AudioTrack track) {
                AudioTrackInfo info = track.getInfo();
                trackScheduler.enqueue(member, track);
                reply(info, "해당 음악이 " + trackScheduler.size() + "번째 대기열에 추가 되었습니다.");
            }

            @Override
            public void playlistLoaded(AudioPlaylist playlist) {
                int start = trackScheduler.size() + 1;
                int end = start + playlist.getTracks().size();
                for (AudioTrack track : playlist.getTracks()) {
                    trackScheduler.enqueue(member, track);
                }
                AudioTrackInfo info = playlist.getSelectedTrack().getInfo();
                reply(info, "해당 플레이리스트가 " + start + "~" + end + "번째 대기열에 추가 되었습니다.");
            }

            @Override
            public void noMatches() {
                EmbedBuilder builder = new EmbedBuilder()
                        .setColor(Color.decode("#f1554a"))
                        .setTitle("검색 데이터가 존재하지 않습니다")
                        .setDescription(query)
                        .setFooter(UserUtil.getName(member));
                replyTo.replyEmbeds(builder.build()).queue();
            }

            @Override
            public void loadFailed(FriendlyException exception) {
                EmbedBuilder builder = new EmbedBuilder()
                        .setColor(Color.decode("#f1554a"))
                        .setTitle("검색 데이터 로드에 실패했습니다")
                        .setDescription("해당 메시지 링크와 함께 관리자에게 문의해주세요")
                        .setFooter(UserUtil.getName(member))
                        .addField(
                                exception.getClass().getName(),
                                StackTraceUtil.convertDiscord(exception),
                                false
                        );
                replyTo.replyEmbeds(builder.build()).queue();
            }

            private void reply(AudioTrackInfo info, String description) {
                EmbedBuilder builder = new EmbedBuilder()
                        .setColor(Color.decode("#5e71ef"))
                        .setTitle(info.title, info.uri)
                        .setDescription(description)
                        .setFooter(UserUtil.getName(member))
                        .addField(
                                "재생 시간",
                                DurationUtil.formatDuration((int) (info.length / 1000)),
                                false
                        );
                replyTo.replyEmbeds(builder.build()).queue();
            }
        });
    }
}
