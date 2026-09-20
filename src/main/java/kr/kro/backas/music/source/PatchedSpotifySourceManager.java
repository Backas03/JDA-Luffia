package kr.kro.backas.music.source;

import com.github.topi314.lavasrc.ExtendedAudioPlaylist;
import com.github.topi314.lavasrc.mirror.MirroringAudioTrackResolver;
import com.github.topi314.lavasrc.spotify.SpotifyAudioTrack;
import com.github.topi314.lavasrc.spotify.SpotifySourceManager;
import com.sedmelluq.discord.lavaplayer.player.AudioPlayerManager;
import com.sedmelluq.discord.lavaplayer.tools.FriendlyException;
import com.sedmelluq.discord.lavaplayer.tools.JsonBrowser;
import com.sedmelluq.discord.lavaplayer.track.AudioItem;
import com.sedmelluq.discord.lavaplayer.track.AudioReference;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import com.sedmelluq.discord.lavaplayer.track.AudioTrackInfo;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Collectors;

public class PatchedSpotifySourceManager extends SpotifySourceManager {

    public static final String PLAYLIST_UNSUPPORTED_MESSAGE =
            "스포티파이 플레이리스트는 현재 API 권한으로 읽을 수 없습니다. 곡 또는 앨범 링크를 이용해주세요.";
    public static final String ARTIST_UNSUPPORTED_MESSAGE =
            "스포티파이 아티스트 링크는 현재 API 권한으로 읽을 수 없습니다. 곡 또는 앨범 링크를 이용해주세요.";
    private static final int ALBUM_MAX_PAGES = 4;

    public PatchedSpotifySourceManager(String clientId,
                                       String clientSecret,
                                       String countryCode,
                                       Function<Void, AudioPlayerManager> audioPlayerManager,
                                       MirroringAudioTrackResolver resolver) {
        super(clientId, clientSecret, countryCode, audioPlayerManager, resolver);
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
                AudioTrack track = parseAlbumTrack(item, albumName, albumUrl, artwork);
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
    public AudioItem getPlaylist(String id, boolean preview) {
        throw new FriendlyException(PLAYLIST_UNSUPPORTED_MESSAGE, FriendlyException.Severity.COMMON, null);
    }

    @Override
    public AudioItem getArtist(String id, boolean preview) {
        throw new FriendlyException(ARTIST_UNSUPPORTED_MESSAGE, FriendlyException.Severity.COMMON, null);
    }

    private AudioTrack parseAlbumTrack(JsonBrowser item, String albumName, String albumUrl, String artwork) {
        if (item.get("is_local").asBoolean(false)) return null;
        String trackId = item.get("id").text();
        String title = item.get("name").text();
        if (trackId == null || title == null) return null;
        String url = item.get("external_urls").get("spotify").text();
        JsonBrowser artists = item.get("artists");
        String artistUrl = artists.index(0).get("external_urls").get("spotify").text();
        AudioTrackInfo info = new AudioTrackInfo(
                title,
                joinArtistNames(artists),
                item.get("duration_ms").asLong(0),
                trackId,
                false,
                url,
                artwork,
                null
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

    private static String firstImage(JsonBrowser images) {
        if (images == null || images.isNull() || images.values().isEmpty()) return null;
        return images.index(0).get("url").text();
    }
}
