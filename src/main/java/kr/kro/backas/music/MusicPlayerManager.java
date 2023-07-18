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
        this.trackScheduler = new TrackScheduler(this, this.player);
        player.addListener(this.trackScheduler);
        this.queries = new HashMap<>();
        Guild guild = luffia.getPublishedGuild();
        guild.getAudioManager().setSendingHandler(new AudioForwarder(this.player));
    }

    public AudioPlayerManager getPlayerManager() {
        return manager;
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
}
