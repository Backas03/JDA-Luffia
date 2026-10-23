package kr.kro.backas.music.source;

import kr.kro.backas.config.Config;
import com.github.topi314.lavasrc.mirror.DefaultMirroringAudioTrackResolver;
import com.github.topi314.lavasrc.spotify.SpotifySourceManager;
import com.sedmelluq.discord.lavaplayer.player.AudioConfiguration;
import com.sedmelluq.discord.lavaplayer.player.AudioPlayerManager;
import com.sedmelluq.discord.lavaplayer.player.DefaultAudioPlayerManager;
import dev.lavalink.youtube.YoutubeAudioSourceManager;
import dev.lavalink.youtube.clients.AndroidVrWithThumbnail;
import dev.lavalink.youtube.clients.Ios;
import dev.lavalink.youtube.clients.MWebWithThumbnail;
import dev.lavalink.youtube.clients.MusicWithThumbnail;
import dev.lavalink.youtube.clients.TvHtml5Simply;
import dev.lavalink.youtube.clients.WebEmbeddedWithThumbnail;
import dev.lavalink.youtube.clients.WebWithThumbnail;
import kr.kro.backas.music.audio.MusicOpusFormat;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class MusicSourceRegistry {
    private static final Logger LOGGER = LoggerFactory.getLogger(MusicSourceRegistry.class);

    public static final String[] SPOTIFY_MIRROR_PROVIDERS = {
            "ytsearch:\"%ISRC%\"",
            "ytmsearch:%QUERY%",
            "ytsearch:%QUERY%"
    };
    public static final String SPOTIFY_COUNTRY_CODE = Config.get().sources().spotify().country();
    public static final int SPOTIFY_PAGE_LIMIT = Config.get().sources().spotify().pageLimit();
    public static final int YOUTUBE_PLAYLIST_PAGE_COUNT = Config.get().sources().youtube().playlistPageCount();

    private final AudioPlayerManager audioPlayerManager;
    private final boolean spotifyEnabled;
    @Nullable
    private final SpotifySourceManager spotify;

    public MusicSourceRegistry(@Nullable String spotifyClientId,
                               @Nullable String spotifyClientSecret,
                               @Nullable String spotifyRefreshToken,
                               @Nullable String spotifySpDc,
                               @Nullable String spotifyTokenEndpoint) {
        DefaultAudioPlayerManager manager = new DefaultAudioPlayerManager();
        AudioConfiguration configuration = manager.getConfiguration();
        configuration.setOpusEncodingQuality(AudioConfiguration.OPUS_QUALITY_MAX);
        configuration.setResamplingQuality(AudioConfiguration.ResamplingQuality.HIGH);
        configuration.setOutputFormat(MusicOpusFormat.discord(Config.get().musicPlayer().opusBitrateKbps()));
        configuration.setFilterHotSwapEnabled(true);

        YoutubeAudioSourceManager youtube = new YoutubeAudioSourceManager(
                true,
                new MusicWithThumbnail(),
                new TvHtml5Simply(),
                new AndroidVrWithThumbnail(),
                new WebWithThumbnail(),
                new MWebWithThumbnail(),
                new WebEmbeddedWithThumbnail(),
                new Ios()
        );
        youtube.setPlaylistPageCount(YOUTUBE_PLAYLIST_PAGE_COUNT);
        manager.registerSourceManager(youtube);

        this.spotify = registerSpotify(manager, spotifyClientId, spotifyClientSecret, spotifyRefreshToken, spotifySpDc, spotifyTokenEndpoint);
        this.spotifyEnabled = this.spotify != null;
        this.audioPlayerManager = manager;
    }

    @Nullable
    private static SpotifySourceManager registerSpotify(AudioPlayerManager manager,
                                                        @Nullable String clientId,
                                                        @Nullable String clientSecret,
                                                        @Nullable String refreshToken,
                                                        @Nullable String spDc,
                                                        @Nullable String tokenEndpoint) {
        if (isBlank(clientId) || isBlank(clientSecret)) {
            LOGGER.info("config.yaml 의 sources.spotify.client-id / client-secret 이 비어 있어 스포티파이 소스를 비활성화합니다.");
            return null;
        }
        try {
            SpotifyUserTokenTracker userToken = isBlank(refreshToken)
                    ? null
                    : new SpotifyUserTokenTracker(clientId, clientSecret, refreshToken);
            SpotifySourceManager spotify = new PatchedSpotifySourceManager(
                    clientId,
                    clientSecret,
                    SPOTIFY_COUNTRY_CODE,
                    unused -> manager,
                    new DefaultMirroringAudioTrackResolver(SPOTIFY_MIRROR_PROVIDERS),
                    userToken,
                    isBlank(spDc) ? null : spDc.trim(),
                    isBlank(tokenEndpoint) ? null : tokenEndpoint.trim()
            );
            spotify.setResolveArtistsInSearch(false);
            spotify.setPlaylistPageLimit(SPOTIFY_PAGE_LIMIT);
            spotify.setAlbumPageLimit(SPOTIFY_PAGE_LIMIT);
            manager.registerSourceManager(spotify);
            LOGGER.info("스포티파이 소스 등록 완료 (country={}, playlist={}, lyrics={})", SPOTIFY_COUNTRY_CODE, userToken != null, !isBlank(spDc));
            return spotify;
        } catch (RuntimeException e) {
            LOGGER.warn("스포티파이 소스 등록에 실패했습니다. 유튜브만 사용합니다.", e);
            return null;
        }
    }

    private static boolean isBlank(@Nullable String s) {
        return s == null || s.isBlank();
    }

    @Nullable
    public SpotifySourceManager getSpotify() {
        return spotify;
    }

    public AudioPlayerManager getAudioPlayerManager() {
        return audioPlayerManager;
    }

    public boolean isSpotifyEnabled() {
        return spotifyEnabled;
    }

    public void shutdown() {
        audioPlayerManager.shutdown();
    }
}
