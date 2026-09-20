package kr.kro.backas.music.source;

import com.github.topi314.lavasrc.mirror.DefaultMirroringAudioTrackResolver;
import com.github.topi314.lavasrc.spotify.SpotifySourceManager;
import com.sedmelluq.discord.lavaplayer.format.StandardAudioDataFormats;
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
    public static final String SPOTIFY_COUNTRY_CODE = "KR";
    public static final int SPOTIFY_PAGE_LIMIT = 10;
    public static final int YOUTUBE_PLAYLIST_PAGE_COUNT = 10;

    private final AudioPlayerManager audioPlayerManager;
    private final boolean spotifyEnabled;

    public MusicSourceRegistry(@Nullable String spotifyClientId,
                               @Nullable String spotifyClientSecret,
                               @Nullable String spotifyRefreshToken) {
        DefaultAudioPlayerManager manager = new DefaultAudioPlayerManager();
        AudioConfiguration configuration = manager.getConfiguration();
        configuration.setOpusEncodingQuality(AudioConfiguration.OPUS_QUALITY_MAX);
        configuration.setResamplingQuality(AudioConfiguration.ResamplingQuality.HIGH);
        configuration.setOutputFormat(StandardAudioDataFormats.DISCORD_OPUS);

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

        this.spotifyEnabled = registerSpotify(manager, spotifyClientId, spotifyClientSecret, spotifyRefreshToken);
        this.audioPlayerManager = manager;
    }

    private static boolean registerSpotify(AudioPlayerManager manager,
                                           @Nullable String clientId,
                                           @Nullable String clientSecret,
                                           @Nullable String refreshToken) {
        if (isBlank(clientId) || isBlank(clientSecret)) {
            LOGGER.info("BotSecret.SPOTIFY_CLIENT_ID / SPOTIFY_CLIENT_SECRET 이 비어 있어 스포티파이 소스를 비활성화합니다.");
            return false;
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
                    userToken
            );
            spotify.setResolveArtistsInSearch(false);
            spotify.setPlaylistPageLimit(SPOTIFY_PAGE_LIMIT);
            spotify.setAlbumPageLimit(SPOTIFY_PAGE_LIMIT);
            manager.registerSourceManager(spotify);
            LOGGER.info("스포티파이 소스 등록 완료 (country={}, playlist={})", SPOTIFY_COUNTRY_CODE, userToken != null);
            return true;
        } catch (RuntimeException e) {
            LOGGER.warn("스포티파이 소스 등록에 실패했습니다. 유튜브만 사용합니다.", e);
            return false;
        }
    }

    private static boolean isBlank(@Nullable String s) {
        return s == null || s.isBlank();
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
