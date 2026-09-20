package kr.kro.backas.music;

import com.github.natanbc.lavadsp.karaoke.KaraokePcmAudioFilter;
import com.github.natanbc.lavadsp.timescale.TimescalePcmAudioFilter;
import com.sedmelluq.discord.lavaplayer.filter.AudioFilter;
import com.sedmelluq.discord.lavaplayer.filter.FloatPcmAudioFilter;
import com.sedmelluq.discord.lavaplayer.filter.equalizer.Equalizer;
import com.sedmelluq.discord.lavaplayer.player.AudioPlayer;
import com.sedmelluq.discord.lavaplayer.player.AudioPlayerManager;
import com.sedmelluq.discord.lavaplayer.player.event.AudioEventAdapter;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import kr.kro.backas.Main;
import kr.kro.backas.SharedConstant;
import kr.kro.backas.music.filter.ConfiguredEqualizer;
import kr.kro.backas.music.filter.KaraokeMode;
import kr.kro.backas.music.filter.VocalEchoFilter;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Activity;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.channel.concrete.VoiceChannel;
import net.dv8tion.jda.api.entities.channel.unions.AudioChannelUnion;
import net.dv8tion.jda.api.managers.AudioManager;
import org.jetbrains.annotations.NotNull;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

public class MusicPlayerClient {
    public static final int DEFAULT_VOLUME = 10;
    public static final float KARAOKE_ECHO_SECONDS = 0.30f;
    public static final float KARAOKE_ECHO_DECAY = 0.50f;
    public static final float KARAOKE_CENTER_GAIN = 0.85f;

    private final AudioPlayerManager audioPlayerManager;
    private final JDA musicBot;
    private final MusicTrack musicTrack;
    private final AudioPlayer audioPlayer;
    private volatile double currentPlaySpeed = 1.0;
    private volatile KaraokeMode karaokeMode = KaraokeMode.OFF;
    private volatile double realPositionMs;
    private volatile ConfiguredEqualizer currentEqualizer = ConfiguredEqualizer.NORMAL;

    public MusicPlayerClient(JDA musicBot, AudioPlayerManager sharedAudioPlayerManager) {
        this.musicBot = musicBot;
        this.audioPlayerManager = sharedAudioPlayerManager;

        this.audioPlayer = this.audioPlayerManager.createPlayer();
        this.audioPlayer.setVolume(DEFAULT_VOLUME);
        this.audioPlayer.addListener(new AudioEventAdapter() {
            @Override
            public void onTrackStart(AudioPlayer player, AudioTrack track) {
                realPositionMs = track.getPosition();
            }
        });

        this.musicTrack = new MusicTrack(this, this.audioPlayer, this.musicBot);
        MusicTrackHandler trackHandler = new MusicTrackHandler(this.musicTrack, this.musicBot);
        this.audioPlayer.addListener(trackHandler);

        Guild guild = musicBot.getGuildById(SharedConstant.PUBLISHED_GUILD_ID);
        if (guild == null) {
            String joined = musicBot.getGuilds().stream()
                    .map(g -> g.getName() + "(" + g.getId() + ")")
                    .collect(Collectors.joining(", "));
            throw new IllegalStateException("봇이 SharedConstant.PUBLISHED_GUILD_ID=" + SharedConstant.PUBLISHED_GUILD_ID
                    + " 서버에 없습니다. 현재 참여 중인 서버: " + joined);
        }
        guild.getAudioManager().setSendingHandler(new AudioForwarder(this));
        updateFilter();
    }

    public AudioPlayer getAudioPlayer() {
        return audioPlayer;
    }

    public JDA getMusicBot() {
        return musicBot;
    }

    public void setPlaySpeed(double speed) {
        this.currentPlaySpeed = speed;
        updateFilter();
    }

    public void setKaraokeMode(KaraokeMode mode) {
        this.karaokeMode = mode;
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
        double speed = currentPlaySpeed;
        KaraokeMode karaoke = karaokeMode;
        ConfiguredEqualizer equalizerPreset = currentEqualizer;
        boolean speedActive = Math.abs(speed - 1.0) > 0.001;
        if (!speedActive && !karaoke.isActive() && equalizerPreset.isFlat()) {
            this.audioPlayer.setFilterFactory(null);
            return;
        }
        this.audioPlayer.setFilterFactory((track, format, output) -> {
            List<AudioFilter> chain = new ArrayList<>();
            FloatPcmAudioFilter downstream = output;
            if (speedActive) {
                TimescalePcmAudioFilter speedFilter = new TimescalePcmAudioFilter(downstream, format.channelCount, format.sampleRate)
                        .setSpeed(speed);
                chain.add(0, speedFilter);
                downstream = speedFilter;
            }
            if (karaoke == KaraokeMode.ECHO) {
                VocalEchoFilter echoFilter = new VocalEchoFilter(downstream, format.sampleRate, format.channelCount,
                        KARAOKE_ECHO_SECONDS, KARAOKE_ECHO_DECAY, KARAOKE_CENTER_GAIN);
                chain.add(0, echoFilter);
                downstream = echoFilter;
            } else if (karaoke == KaraokeMode.VOCAL_REMOVE) {
                KaraokePcmAudioFilter vocalRemove = new KaraokePcmAudioFilter(downstream, format.channelCount, format.sampleRate)
                        .setLevel(1.0f)
                        .setMonoLevel(1.0f);
                chain.add(0, vocalRemove);
                downstream = vocalRemove;
            }
            if (!equalizerPreset.isFlat()) {
                Equalizer equalizer = new Equalizer(format.channelCount, downstream);
                equalizerPreset.applyTo(equalizer);
                chain.add(0, equalizer);
            }
            return chain;
        });
    }

    public int getVolume() {
        return audioPlayer.getVolume();
    }

    public void setVolume(int volume) {
        audioPlayer.setVolume(volume);
    }

    public KaraokeMode getKaraokeMode() {
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

    public boolean enqueueOrPlay(MusicSelection selection, @NotNull VoiceChannel memberChannel) {
        if (!hasJoinedToVoiceChannel()) {
            connectToVoiceChannel(memberChannel);
        }
        AudioTrack track = selection.getSelectedTrack();
        track.setUserData(selection);
        return musicTrack.enqueueOrPlay(track);
    }

    public EmbedBuilder enqueue(MusicSelection selection, @NotNull VoiceChannel memberChannel) {
        boolean enqueued = enqueueOrPlay(selection, memberChannel);
        AudioTrack track = selection.getSelectedTrack();
        if (!enqueued) return MusicEmbeds.play(track, musicBot);
        return MusicEmbeds.enqueue(track, musicBot, musicTrack.getTrackQueue().size());
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
                .getAudioManager()
                .openAudioConnection(channel);
        musicBot.getPresence().setActivity(Activity.playing(channel.getName() + "에서 플레이"));
    }

    public void disconnectFromVoiceChannelAndResetTrack() {
        AudioManager manager = musicBot
                .getGuildById(SharedConstant.PUBLISHED_GUILD_ID)
                .getAudioManager();
        if (!manager.isConnected()) return;
        manager.closeAudioConnection();

        musicBot.getPresence().setActivity(Activity.playing(SharedConstant.DEFAULT_ACTIVITY));

        musicTrack.reset();
        audioPlayer.setVolume(DEFAULT_VOLUME);
    }

    public void shutdownGracefully() throws InterruptedException {
        musicTrack.reset();
        audioPlayer.destroy();
        if (!musicBot.awaitShutdown(Duration.ofSeconds(Main.SHUTDOWN_TIMEOUT))) {
            musicBot.shutdownNow();
            musicBot.awaitShutdown();
        }
    }

    public AudioPlayerManager getAudioPlayerManager() {
        return audioPlayerManager;
    }
}
