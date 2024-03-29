package kr.kro.backas.music;

import com.github.natanbc.lavadsp.timescale.TimescalePcmAudioFilter;
import com.sedmelluq.discord.lavaplayer.filter.equalizer.Equalizer;
import com.sedmelluq.discord.lavaplayer.format.StandardAudioDataFormats;
import com.sedmelluq.discord.lavaplayer.player.AudioConfiguration;
import com.sedmelluq.discord.lavaplayer.player.AudioPlayer;
import com.sedmelluq.discord.lavaplayer.player.AudioPlayerManager;
import com.sedmelluq.discord.lavaplayer.player.DefaultAudioPlayerManager;
import com.sedmelluq.discord.lavaplayer.player.event.AudioEventAdapter;
import com.sedmelluq.discord.lavaplayer.source.youtube.YoutubeAudioSourceManager;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import kr.kro.backas.Main;
import kr.kro.backas.SharedConstant;
import kr.kro.backas.music.filter.ConfiguredEqualizer;
import kr.kro.backas.music.filter.EchoFilter;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Activity;
import net.dv8tion.jda.api.entities.channel.concrete.VoiceChannel;
import net.dv8tion.jda.api.entities.channel.unions.AudioChannelUnion;
import net.dv8tion.jda.api.managers.AudioManager;
import org.jetbrains.annotations.NotNull;

import java.awt.*;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

public class MusicPlayerClient {

    private final AudioPlayerManager audioPlayerManager;
    private final JDA musicBot;
    private final MusicTrack musicTrack;
    private final AudioPlayer audioPlayer;
    private double currentPlaySpeed = 1.0;
    private boolean karaokeMode = false;
    /* lavaplayer position is wrong if you change the speed */
    private double realPositionMs;
    private ConfiguredEqualizer currentEqualizer = ConfiguredEqualizer.NORMAL;

    public MusicPlayerClient(JDA musicBot) {
        this.musicBot = musicBot;
        this.audioPlayerManager = new DefaultAudioPlayerManager();
        this.audioPlayerManager.registerSourceManager(new YoutubeAudioSourceManager());
        this.audioPlayerManager.getConfiguration()
                .setOpusEncodingQuality(AudioConfiguration.OPUS_QUALITY_MAX);
        this.audioPlayerManager.getConfiguration()
                .setResamplingQuality(AudioConfiguration.ResamplingQuality.HIGH);
        this.audioPlayerManager.getConfiguration()
                .setOutputFormat(StandardAudioDataFormats.DISCORD_OPUS);
        YoutubeAudioSourceManager youtubeAudioSourceManager =
                this.audioPlayerManager.source(YoutubeAudioSourceManager.class);
        // there is 100 videos per page and the maximum playlist size is 5000
        youtubeAudioSourceManager.setPlaylistPageCount(50);

        this.audioPlayer = this.audioPlayerManager.createPlayer();
        this.audioPlayer.addListener(new AudioEventAdapter() {
            @Override
            public void onTrackStart(AudioPlayer player, AudioTrack track) {
                realPositionMs = track.getPosition();
            }
        });

        this.musicTrack = new MusicTrack(this, this.audioPlayer, this.musicBot);
        MusicTrackHandler trackHandler = new MusicTrackHandler(this.musicTrack);
        this.audioPlayer.addListener(trackHandler);

        musicBot.getGuildById(SharedConstant.PUBLISHED_GUILD_ID)
                .getAudioManager()
                .setSendingHandler(new AudioForwarder(this));
        updateFilter();
    }

    public AudioPlayer getAudioPlayer() {
        return audioPlayer;
    }

    public JDA getMusicBot() {
        return musicBot;
    }


    // 재생중에는 한번만 변경 가능 그 노래가 끝나고 설정됨
    public void setPlaySpeed(double speed) {
        this.currentPlaySpeed = speed;
        updateFilter();
    }

    public void setKaraokeMode(boolean status) {
        this.karaokeMode = status;
        updateFilter();
    }

    public void setEqualizer(ConfiguredEqualizer equalizer) {
        this.currentEqualizer = equalizer;
        updateFilter();
    }

    public ConfiguredEqualizer getCurrentEqualizer() {
        return currentEqualizer;
    }

    private void updateFilter() {
        this.audioPlayer.setFilterFactory((track, format, output) -> {
            TimescalePcmAudioFilter speedFilter = new TimescalePcmAudioFilter(output, format.channelCount, format.sampleRate)
                    .setSpeed(currentPlaySpeed);

            EchoFilter echoFilter = new EchoFilter(speedFilter, format.sampleRate, format.channelCount);
            if (karaokeMode) {
                echoFilter.setEchoLength(0.47f);
                echoFilter.setEchoDecay(0.22f);
            }
            Equalizer equalizer = new Equalizer(format.channelCount, echoFilter);
            currentEqualizer.applyTo(equalizer);

            return List.of(equalizer, echoFilter, speedFilter);
        });
    }

    public boolean isKaraokeMode() {
        return karaokeMode;
    }

    public double getCurrentPlaySpeed() {
        return currentPlaySpeed;
    }

    public VoiceChannel getJoinedVoiceChannel() {
        AudioChannelUnion audioChannelUnion = musicBot.getGuildById(SharedConstant.PUBLISHED_GUILD_ID)
                .getAudioManager()
                .getConnectedChannel();
        if (audioChannelUnion == null) return null;
        return audioChannelUnion.asVoiceChannel();
    }

    public boolean isPaused() {
        return audioPlayer.isPaused();
    }

    public double getRealPositionMs() {
        return realPositionMs;
    }

    public boolean hasJoinedToVoiceChannel() {
        return getJoinedVoiceChannel() != null;
    }

    public EmbedBuilder enqueue(MusicSelection selection, VoiceChannel memberChannel) {
        if (!hasJoinedToVoiceChannel()) {
            if (memberChannel == null) {
                EmbedBuilder builder = new EmbedBuilder()
                        .setColor(Color.decode("#f1554a"))
                        .setTitle("음악을 재생할 수 없습니다.")
                        .setDescription("참여한 음성채팅방을 찾을 수 없습니다. 재생을 종료합니다")
                        .setFooter(SharedConstant.RELEASE_VERSION);
                disconnectFromVoiceChannelAndResetTrack();
                return builder;
            }
            connectToVoiceChannel(memberChannel);
        }
        AudioTrack track = selection.getSelectedTrack();
        track.setUserData(selection);

        // call event
        if (!musicTrack.enqueueOrPlay(track)) return MusicTrackHandler.getPlayMessage(track, musicBot);
        return MusicTrackHandler.getEnqueueMessage(
                track,
                musicBot,
                musicTrack.getTrackQueue().size());
    }

    public List<AudioTrack> getTrackQueue() {
        return new ArrayList<>(musicTrack.getTrackQueue());
    }

    public boolean isNowPlaying() {
        return musicTrack.isNowPlaying();
    }

    public AudioTrack getCurrentPlaying() {
        return audioPlayer.getPlayingTrack();
    }

    public void updatePosition() {
        realPositionMs = realPositionMs + 20 * currentPlaySpeed;
    }

    public String getRepeatModeName() {
        return musicTrack.getRepeatModeName();
    }

    public void setRepeatMode(RepeatMode mode) {
        musicTrack.setRepeatMode(mode);
    }

    public boolean pause() {
        boolean b = !audioPlayer.isPaused();
        audioPlayer.setPaused(true);
        return b;
    }

    public boolean resume() {
        boolean b = audioPlayer.isPaused();
        audioPlayer.setPaused(false);
        return b;
    }

    public void skipNowPlaying() {
        AudioTrack track = this.audioPlayer.getPlayingTrack();
        if (track != null) track = track.makeClone();
        musicTrack.playNextTrack(track);
    }

    public void connectToVoiceChannel(@NotNull VoiceChannel channel) {
        musicBot.getGuildById(SharedConstant.PUBLISHED_GUILD_ID)
                .getAudioManager() // cannot be null
                .openAudioConnection(channel);
        musicBot.getPresence().setActivity(Activity.playing(channel.getName() + "에서 플레이"));
    }

    public void disconnectFromVoiceChannelAndResetTrack() {
        AudioManager manager = musicBot
                .getGuildById(SharedConstant.PUBLISHED_GUILD_ID)
                .getAudioManager(); // cannot be null
        if (!manager.isConnected()) return;
        manager.closeAudioConnection();

        musicBot.getPresence().setActivity(Activity.playing(SharedConstant.DEFAULT_ACTIVITY));

        musicTrack.reset();
    }

    public void shutdownGracefully() throws InterruptedException {
        musicTrack.reset();
        if (!musicBot.awaitShutdown(Duration.ofSeconds(Main.SHUTDOWN_TIMEOUT))) {
            musicBot.shutdownNow();
            musicBot.awaitShutdown();
        }
    }

    public AudioPlayerManager getAudioPlayerManager() {
        return audioPlayerManager;
    }
}
