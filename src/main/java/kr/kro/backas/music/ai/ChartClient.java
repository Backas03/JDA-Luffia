package kr.kro.backas.music.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

public class ChartClient {

    public static final int MAX_LIMIT = 50;
    private static final long CACHE_MS = 30 * 60 * 1000;
    private static final Pattern COUNTRY = Pattern.compile("[a-z]{2}");
    private static final String FEED_URL = "https://rss.marketingtools.apple.com/api/v2/%s/music/most-played/%d/songs.json";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    public record ChartSong(String title, String artist) {
    }

    private record Cached(long fetchedAt, List<ChartSong> songs) {
    }

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
    private final Map<String, Cached> cache = new ConcurrentHashMap<>();

    public List<ChartSong> topSongs(String country, int limit) throws IOException {
        String code = country == null ? "" : country.strip().toLowerCase(Locale.ROOT);
        if (!COUNTRY.matcher(code).matches()) throw new IOException("invalid country code: " + country);
        Cached cached = cache.get(code);
        long now = System.currentTimeMillis();
        if (cached == null || now - cached.fetchedAt() > CACHE_MS) {
            cached = new Cached(now, fetch(code));
            cache.put(code, cached);
        }
        List<ChartSong> songs = cached.songs();
        return songs.subList(0, Math.min(Math.max(1, limit), songs.size()));
    }

    private List<ChartSong> fetch(String code) throws IOException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(String.format(FEED_URL, code, MAX_LIMIT)))
                .timeout(Duration.ofSeconds(10))
                .GET()
                .build();
        HttpResponse<String> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("chart request interrupted", e);
        }
        if (response.statusCode() / 100 != 2) throw new IOException("chart " + response.statusCode());
        List<ChartSong> songs = new ArrayList<>();
        for (JsonNode entry : MAPPER.readTree(response.body()).path("feed").path("results")) {
            String title = entry.path("name").asText("").strip();
            String artist = entry.path("artistName").asText("").strip();
            if (!title.isEmpty()) songs.add(new ChartSong(title, artist));
        }
        return songs;
    }
}
