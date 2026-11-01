package kr.kro.backas.music.source;

import com.github.topi314.lavasrc.ExtendedAudioPlaylist;
import com.github.topi314.lavasrc.mirror.MirroringAudioSourceManager;
import com.github.topi314.lavasrc.mirror.MirroringAudioTrackResolver;
import com.github.topi314.lavasrc.spotify.SpotifyAudioTrack;
import com.github.topi314.lavasrc.spotify.SpotifyPartnerApiClient;
import com.github.topi314.lavasrc.spotify.SpotifySourceManager;
import com.sedmelluq.discord.lavaplayer.player.AudioPlayerManager;
import com.sedmelluq.discord.lavaplayer.tools.FriendlyException;
import com.sedmelluq.discord.lavaplayer.tools.JsonBrowser;
import com.sedmelluq.discord.lavaplayer.tools.io.HttpInterfaceManager;
import com.sedmelluq.discord.lavaplayer.track.AudioItem;
import com.sedmelluq.discord.lavaplayer.track.AudioPlaylist;
import com.sedmelluq.discord.lavaplayer.track.AudioReference;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import com.sedmelluq.discord.lavaplayer.track.AudioTrackInfo;
import org.apache.http.client.config.RequestConfig;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.lang.reflect.Field;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Collectors;

public class PatchedSpotifySourceManager extends SpotifySourceManager {

    public static final String PLAYLIST_NOT_CONFIGURED_MESSAGE =
            "스포티파이 플레이리스트를 읽으려면 봇에 계정 로그인이 필요합니다. 관리자가 spotifyLogin 을 실행해 토큰을 등록해야 합니다.";
    public static final String PLAYLIST_FORBIDDEN_MESSAGE =
            "이 플레이리스트는 Spotify API 정책 상 읽을 수 없습니다. 봇에 로그인된 Spotify 계정이 만들었거나 라이브러리에 저장한 플레이리스트만 지원됩니다.";
    public static final String ARTIST_UNSUPPORTED_MESSAGE =
            "스포티파이 아티스트 링크는 현재 API 권한으로 읽을 수 없습니다. 곡 또는 앨범 링크를 이용해주세요.";
    private static final int ALBUM_MAX_PAGES = 10;
    private static final int PLAYLIST_PAGE_SIZE = 100;
    private static final int PLAYLIST_MAX_PAGES = 10;

    private static final Logger LOGGER = LoggerFactory.getLogger(PatchedSpotifySourceManager.class);

    private final SpotifyUserTokenTracker userToken;
    @Nullable
    private final SpotifyPartnerApiClient webPlayer;
    private final HttpClient httpClient = HttpClient.newHttpClient();

    public PatchedSpotifySourceManager(String clientId,
                                       String clientSecret,
                                       String countryCode,
                                       Function<Void, AudioPlayerManager> audioPlayerManager,
                                       MirroringAudioTrackResolver resolver,
                                       @Nullable SpotifyUserTokenTracker userToken,
                                       @Nullable String spDc,
                                       @Nullable String tokenEndpoint) {
        super(clientId, clientSecret, false, tokenEndpoint, spDc, countryCode, audioPlayerManager, resolver);
        this.userToken = userToken;
        this.webPlayer = tokenEndpoint == null || tokenEndpoint.isBlank() ? null : webPlayerClient();
    }

    @Override
    public AudioItem getAlbum(String id, boolean preview) throws IOException {
        JsonBrowser album = getJson(API_BASE + "albums/" + id);
        if (album == null) {
            return AudioReference.NO_TRACK;
        }
        String albumName = album.get("name").text();
        String albumUrl = album.get("external_urls").get("spotify").text();
        String artwork = firstImage(album.get("images"));
        String albumArtists = joinArtistNames(album.get("artists"));
        int totalTracks = (int) album.get("total_tracks").asLong(0);

        List<AudioTrack> tracks = new ArrayList<>();
        JsonBrowser page = album.get("tracks");
        int pages = 0;
        while (page != null && !page.isNull() && pages < ALBUM_MAX_PAGES) {
            for (JsonBrowser item : page.get("items").values()) {
                AudioTrack track = parseTrack(item, albumName, albumUrl, artwork);
                if (track != null) tracks.add(track);
            }
            String next = page.get("next").text();
            if (next == null || next.isBlank()) break;
            page = getJson(next);
            pages++;
        }
        if (tracks.isEmpty()) {
            return AudioReference.NO_TRACK;
        }
        return new ExtendedAudioPlaylist(albumName, tracks, ExtendedAudioPlaylist.Type.ALBUM,
                albumUrl, artwork, albumArtists, totalTracks);
    }

    @Override
    public void configureRequests(Function<RequestConfig, RequestConfig> configurator) {
        super.configureRequests(configurator);
        HttpInterfaceManager tokenRequests = tokenRequestManager();
        if (tokenRequests != null) tokenRequests.configureRequests(configurator);
    }

    @Nullable
    private HttpInterfaceManager tokenRequestManager() {
        try {
            Field field = MirroringAudioSourceManager.class.getDeclaredField("httpInterfaceManager");
            field.setAccessible(true);
            return (HttpInterfaceManager) field.get(this);
        } catch (ReflectiveOperationException | RuntimeException e) {
            LOGGER.warn("스포티파이 토큰 요청의 HTTP 설정을 바꾸지 못해 기본 타임아웃(3초)을 그대로 씁니다", e);
            return null;
        }
    }

    @Nullable
    private SpotifyPartnerApiClient webPlayerClient() {
        try {
            Field field = SpotifySourceManager.class.getDeclaredField("partnerApiClient");
            field.setAccessible(true);
            return (SpotifyPartnerApiClient) field.get(this);
        } catch (ReflectiveOperationException | RuntimeException e) {
            LOGGER.warn("스포티파이 웹 플레이어 API 를 쓸 수 없어 로그인 계정으로만 플레이리스트를 읽습니다", e);
            return null;
        }
    }

    @Override
    public AudioItem getPlaylist(String id, boolean preview) throws IOException {
        if (webPlayer != null) {
            try {
                AudioItem viaWebPlayer = webPlayer.loadPartnerPlaylist(id, preview, PLAYLIST_PAGE_SIZE * PLAYLIST_MAX_PAGES, this);
                if (viaWebPlayer instanceof AudioPlaylist playlist && !playlist.getTracks().isEmpty()) return viaWebPlayer;
            } catch (IOException | RuntimeException e) {
                LOGGER.info("web player could not read spotify playlist {}, trying the logged-in account: {}", id, e.toString());
            }
        }
        if (userToken == null) {
            throw new FriendlyException(PLAYLIST_NOT_CONFIGURED_MESSAGE, FriendlyException.Severity.COMMON, null);
        }
        JsonBrowser playlist = getUserJson(API_BASE + "playlists/" + id);
        if (playlist == null) {
            return AudioReference.NO_TRACK;
        }
        String name = playlist.get("name").text();
        String url = playlist.get("external_urls").get("spotify").text();
        String artwork = firstImage(playlist.get("images"));
        String owner = playlist.get("owner").get("display_name").text();

        List<AudioTrack> tracks = new ArrayList<>();
        int total = 0;
        String pageUrl = API_BASE + "playlists/" + id + "/items?limit=" + PLAYLIST_PAGE_SIZE + "&offset=0";
        int pages = 0;
        while (pageUrl != null && pages < PLAYLIST_MAX_PAGES) {
            JsonBrowser page = getUserJson(pageUrl);
            if (page == null) break;
            total = (int) page.get("total").asLong(total);
            for (JsonBrowser entry : page.get("items").values()) {
                JsonBrowser item = entry.get("item");
                if (item.isNull()) item = entry.get("track");
                if (item.isNull() || !"track".equals(item.get("type").text())) continue;
                JsonBrowser album = item.get("album");
                AudioTrack track = parseTrack(item,
                        album.get("name").text(),
                        album.get("external_urls").get("spotify").text(),
                        firstImage(album.get("images")));
                if (track != null) tracks.add(track);
            }
            String next = page.get("next").text();
            pageUrl = next == null || next.isBlank() ? null : next;
            pages++;
        }
        if (tracks.isEmpty()) {
            return AudioReference.NO_TRACK;
        }
        return new ExtendedAudioPlaylist(name, tracks, ExtendedAudioPlaylist.Type.PLAYLIST,
                url, artwork, owner, total);
    }

    @Override
    public AudioItem getArtist(String id, boolean preview) {
        throw new FriendlyException(ARTIST_UNSUPPORTED_MESSAGE, FriendlyException.Severity.COMMON, null);
    }

    @Nullable
    private JsonBrowser getUserJson(String url) throws IOException {
        HttpResponse<String> response = sendWithUserToken(url);
        if (response.statusCode() == 401) {
            userToken.invalidate();
            response = sendWithUserToken(url);
        }
        int status = response.statusCode();
        if (status == 404) return null;
        if (status == 401 || status == 403) {
            throw new FriendlyException(PLAYLIST_FORBIDDEN_MESSAGE, FriendlyException.Severity.COMMON, null);
        }
        if (status / 100 != 2) {
            throw new IOException("Spotify " + status + " for " + url + ": " + response.body());
        }
        return JsonBrowser.parse(response.body());
    }

    private HttpResponse<String> sendWithUserToken(String url) throws IOException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .header("Authorization", "Bearer " + userToken.getAccessToken())
                .GET()
                .build();
        try {
            return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("request interrupted", e);
        }
    }

    @Nullable
    private AudioTrack parseTrack(JsonBrowser item, String albumName, String albumUrl, String artwork) {
        if (item.get("is_local").asBoolean(false)) return null;
        String trackId = item.get("id").text();
        String title = item.get("name").text();
        if (trackId == null || title == null) return null;
        String url = item.get("external_urls").get("spotify").text();
        JsonBrowser artists = item.get("artists");
        String artistUrl = artists.index(0).get("external_urls").get("spotify").text();
        String isrc = item.get("external_ids").get("isrc").text();
        AudioTrackInfo info = new AudioTrackInfo(
                title,
                joinArtistNames(artists),
                item.get("duration_ms").asLong(0),
                trackId,
                false,
                url,
                artwork,
                isrc
        );
        return new SpotifyAudioTrack(info, albumName, albumUrl, artistUrl, null, null, false, this);
    }

    private static String joinArtistNames(JsonBrowser artists) {
        if (artists == null || artists.isNull()) return "";
        return artists.values().stream()
                .map(a -> a.get("name").text())
                .filter(n -> n != null && !n.isBlank())
                .collect(Collectors.joining(", "));
    }

    @Nullable
    private static String firstImage(JsonBrowser images) {
        if (images == null || images.isNull() || images.values().isEmpty()) return null;
        return images.index(0).get("url").text();
    }
}
