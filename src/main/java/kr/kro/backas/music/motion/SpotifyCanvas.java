package kr.kro.backas.music.motion;

import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class SpotifyCanvas {

    static final String CACHE_URL = "https://spclient.wg.spotify.com/canvaz-cache/v0/canvases";
    static final String QUERY_URL = "https://api-partner.spotify.com/pathfinder/v1/query";
    static final String QUERY_HASH = "1b1e1915481c99f4349af88268c6b49a2b601cf0db7bca8749b5dd75088486fc";
    private static final String USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/134.0.6998.178 Spotify/1.2.65.255 Safari/537.36";
    private static final Pattern VIDEO_URL = Pattern.compile("https://[A-Za-z0-9._~/%-]+?\\.mp4");
    private static final Pattern TRACK_ID = Pattern.compile("[A-Za-z0-9]{22}");
    private static final Logger LOGGER = LoggerFactory.getLogger(SpotifyCanvas.class);
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    @FunctionalInterface
    interface TokenSource {
        String token() throws IOException;
    }

    private SpotifyCanvas() {
    }

    @Nullable
    static String find(TokenSource tokens, String trackId) throws IOException, InterruptedException {
        if (!TRACK_ID.matcher(trackId).matches()) return null;
        String token = tokens.token();
        if (token == null || token.isBlank()) throw new IOException("spotify web player token is empty");
        IOException failure = null;
        try {
            String url = videoUrl(send(cacheRequest(token, trackId)));
            if (url != null) return url;
        } catch (IOException e) {
            failure = e;
            LOGGER.debug("spotify canvas cache lookup failed for {}: {}", trackId, e.toString());
        }
        try {
            return videoUrl(send(queryRequest(token, trackId)));
        } catch (IOException e) {
            if (failure != null) e.addSuppressed(failure);
            throw e;
        }
    }

    private static byte[] send(HttpRequest request) throws IOException, InterruptedException {
        HttpResponse<byte[]> response = HTTP.send(request, HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() / 100 != 2) throw new IOException("spotify answered " + response.statusCode() + " for " + request.uri());
        return response.body();
    }

    static HttpRequest cacheRequest(String token, String trackId) {
        return HttpRequest.newBuilder(URI.create(CACHE_URL))
                .timeout(Duration.ofSeconds(8))
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "application/x-protobuf")
                .header("Accept", "application/protobuf")
                .header("User-Agent", USER_AGENT)
                .POST(HttpRequest.BodyPublishers.ofByteArray(cacheBody(trackId)))
                .build();
    }

    static HttpRequest queryRequest(String token, String trackId) {
        String body = "{\"variables\":{\"uri\":\"spotify:track:" + trackId + "\"},\"operationName\":\"canvas\","
                + "\"extensions\":{\"persistedQuery\":{\"version\":1,\"sha256Hash\":\"" + QUERY_HASH + "\"}}}";
        return HttpRequest.newBuilder(URI.create(QUERY_URL))
                .timeout(Duration.ofSeconds(8))
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "application/json")
                .header("User-Agent", USER_AGENT)
                .header("Referer", "https://open.spotify.com/")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
    }

    static byte[] cacheBody(String trackId) {
        byte[] uri = ("spotify:track:" + trackId).getBytes(StandardCharsets.US_ASCII);
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        body.write(0x0A);
        body.write(uri.length + 2);
        body.write(0x0A);
        body.write(uri.length);
        body.writeBytes(uri);
        return body.toByteArray();
    }

    @Nullable
    static String videoUrl(byte[] response) {
        String text = new String(response, StandardCharsets.ISO_8859_1).replace("\\/", "/");
        Matcher matcher = VIDEO_URL.matcher(text);
        return matcher.find() ? matcher.group() : null;
    }
}
