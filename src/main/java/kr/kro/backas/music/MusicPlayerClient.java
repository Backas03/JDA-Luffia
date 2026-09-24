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
import kr.kro.backas.music.filter.ConfiguredEqualizer;
import kr.kro.backas.music.filter.KaraokeMode;
import kr.kro.backas.music.filter.VocalEchoFilter;
import kr.kro.backas.music.lyrics.LyricsPresenter;
import kr.kro.backas.music.lyrics.LyricsSession;
import net.dv8tion.jda.api.entities.channel.middleman.MessageChannel;
import org.jetbrains.annotations.Nullable;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.channel.concrete.VoiceChannel;
import net.dv8tion.jda.api.entities.channel.unions.AudioChannelUnion;
import net.dv8tion.jda.api.managers.AudioManager;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

public class MusicPlayerClient {
    public static final int DEFAULT_VOLUME = 10;
    public static final float KARAOKE_ECHO_SECONDS = 0.30f;
    public static final float KARAOKE_ECHO_DECAY = 0.15f;
    public static final float KARAOKE_CENTER_GAIN = 1.3f;

    private final AudioPlayerManager audioPlayerManager;
    private final JDA musicBot;
    private final long guildId;
    private final MusicTrack musicTrack;
    private final AudioPlayer audioPlayer;
    private volatile double currentPlaySpeed = 1.0;
    private volatile KaraokeMode karaokeMode = KaraokeMode.OFF;
    private volatile double realPositionMs;
    private volatile ConfiguredEqualizer currentEqualizer = ConfiguredEqualizer.NORMAL;
    private volatile LyricsSession lyricsSession;
    private volatile boolean autoLyricsEnabled = true;
    private volatile MessageChannel lyricsChannel;
    private volatile long lyricsOffsetMs = LyricsSession.DEFAULT_OFFSET_MS;

    public MusicPlayerClient(JDA musicBot, Guild guild, AudioPlayerManager sharedAudioPlayerManager) {
        this.musicBot = musicBot;
        this.guildId = guild.getIdLong();
        this.audioPlayerManager = sharedAudioPlayerManager;

        this.audioPlayer = this.audioPlayerManager.createPlayer();
        this.audioPlayer.setVolume(DEFAULT_VOLUME);
        this.audioPlayer.addListener(new AudioEventAdapter() {
            @Override
            public void onTrackStart(AudioPlayer player, AudioTrack track) {
                realPositionMs = track.getPosition();
                onTrackStarted(track);
            }
        });

        this.musicTrack = new MusicTrack(this, this.audioPlayer);
        MusicTrackHandler trackHandler = new MusicTrackHandler(this.musicTrack, this);
        this.audioPlayer.addListener(trackHandler);

        guild.getAudioManager().setSendingHandler(new AudioForwarder(this));
        updateFilter();
    }

    public AudioPlayer getAudioPlayer() {
        return audioPlayer;
    }

    public JDA getMusicBot() {
        return musicBot;
    }

    public long getGuildId() {
        return guildId;
    }

    @Nullable
    public Guild getGuild() {
        return musicBot.getGuildById(guildId);
    }

    @Nullable
    private AudioManager audioManager() {
        Guild guild = getGuild();
        return guild == null ? null : guild.getAudioManager();
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

    public @Nullable LyricsSession getLyricsSession() {
        return lyricsSession;
    }

    public void setLyricsSession(@Nullable LyricsSession session) {
        this.lyricsSession = session;
    }

    public boolean stopLyrics(String reason) {
        LyricsSession session = lyricsSession;
        if (session == null) return false;
        lyricsSession = null;
        session.stop(reason);
        return true;
    }

    public void enableAutoLyrics(MessageChannel channel, long offsetMs) {
        this.autoLyricsEnabled = true;
        this.lyricsChannel = channel;
        this.lyricsOffsetMs = offsetMs;
    }

    public boolean disableAutoLyrics(String reason) {
        boolean wasEnabled = autoLyricsEnabled;
        autoLyricsEnabled = false;
        lyricsChannel = null;
        boolean stopped = stopLyrics(reason);
        return wasEnabled || stopped;
    }

    public boolean isAutoLyricsEnabled() {
        return autoLyricsEnabled;
    }

    private void resetLyricsPreferences() {
        autoLyricsEnabled = true;
        lyricsChannel = null;
        lyricsOffsetMs = LyricsSession.DEFAULT_OFFSET_MS;
    }

    @Nullable
    private MessageChannel lyricsChannelFor(AudioTrack track) {
        MessageChannel channel = lyricsChannel;
        if (channel != null) return channel;
        MusicSelection selection = track.getUserData(MusicSelection.class);
        if (selection == null || selection.getSlashCommandInteractionEvent() == null) return null;
        return selection.getSlashCommandInteractionEvent().getMessageChannel();
    }

    public long getLyricsOffsetMs() {
        return lyricsOffsetMs;
    }

    public void setLyricsOffsetMs(long offsetMs) {
        this.lyricsOffsetMs = offsetMs;
    }

    public boolean isCurrentTrack(AudioTrack track) {
        AudioTrack playing = audioPlayer.getPlayingTrack();
        return playing != null && track != null && playing.getIdentifier().equals(track.getIdentifier());
    }

    private void onTrackStarted(AudioTrack track) {
        if (!autoLyricsEnabled) return;
        MessageChannel channel = lyricsChannelFor(track);
        if (channel == null) return;
        LyricsSession current = lyricsSession;
        if (current != null && current.isForTrack(track)) return;
        LyricsPresenter.presentInChannel(this, track, channel, lyricsOffsetMs);
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
        AudioManager manager = audioManager();
        if (manager == null) return null;
        AudioChannelUnion audioChannelUnion = manager.getConnectedChannel();
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
        boolean enqueued = musicTrack.enqueueOrPlay(track);
        if (enqueued) LyricsPresenter.prefetchNext(this);
        return enqueued;
    }

    public EmbedBuilder enqueue(MusicSelection selection, @NotNull VoiceChannel memberChannel) {
        boolean enqueued = enqueueOrPlay(selection, memberChannel);
        AudioTrack track = selection.getSelectedTrack();
        if (!enqueued) return MusicEmbeds.play(track, getGuild());
        return MusicEmbeds.enqueue(track, getGuild(), musicTrack.getTrackQueue().size());
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
        musicTrack.skip(1);
    }

    public int skip(int count) {
        return musicTrack.skip(Math.max(1, count));
    }

    public void connectToVoiceChannel(@NotNull VoiceChannel channel) {
        if (channel.getGuild().getIdLong() != guildId) {
            throw new IllegalStateException("이 클라이언트의 서버(" + guildId + ")가 아닌 서버의 음성채팅방입니다: "
                    + channel.getGuild().getName() + "(" + channel.getGuild().getId() + ")");
        }
        AudioManager manager = audioManager();
        if (manager == null) {
            throw new IllegalStateException("노래봇 " + musicBot.getSelfUser().getName() + "이(가) 서버 " + guildId + "에 없습니다");
        }
        VoiceChannel own = musicBot.getVoiceChannelById(channel.getIdLong());
        manager.openAudioConnection(own == null ? channel : own);
        Main.getLuffia().getMusicPlayerController().updatePresence(musicBot);
    }

    public void disconnectFromVoiceChannelAndResetTrack() {
        AudioManager manager = audioManager();
        if (manager == null || !manager.isConnected()) return;
        manager.closeAudioConnection();

        musicTrack.reset();
        resetLyricsPreferences();
        audioPlayer.setVolume(DEFAULT_VOLUME);
        Main.getLuffia().getMusicPlayerController().updatePresence(musicBot);
    }

    public void shutdownGracefully() {
        musicTrack.reset();
        audioPlayer.destroy();
    }

    public AudioPlayerManager getAudioPlayerManager() {
        return audioPlayerManager;
    }
}
