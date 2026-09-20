package kr.kro.backas.music.lyrics;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sedmelluq.discord.lavaplayer.track.AudioTrackInfo;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class LrcLibClient {

    private static final Logger LOGGER = LoggerFactory.getLogger(LrcLibClient.class);
    private static final String API_BASE = "https://lrclib.net/api/";
    private static final String USER_AGENT = "JDA-Luffia/1.0 (https://github.com/Backas03/JDA-Luffia)";
    private static final long DURATION_TOLERANCE_SEC = 5;
    private static final Pattern LRC_LINE = Pattern.compile("\\[(\\d{1,2}):(\\d{2})(?:[.:](\\d{1,3}))?](.*)");
    private static final Pattern TITLE_NOISE = Pattern.compile(
            "(?i)\\s*[\\[(【].*?(official|mv|m/v|music video|lyric|audio|visualizer|ver\\.?|version|remaster).*?[\\])】]\\s*|\\s*[|_]\\s*(mv|m/v|official.*)$");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    @Nullable
    public Lyrics find(AudioTrackInfo info) throws IOException {
        String title = cleanTitle(info.title);
        String artist = firstArtist(info.author);
        long durationSec = info.length / 1000;
        JsonNode node = null;
        if (!artist.isBlank()) {
            node = get("get?track_name=" + encode(title) + "&artist_name=" + encode(artist) + "&duration=" + durationSec);
        }
        if (node == null) {
            node = pickBest(get("search?q=" + encode(title + " " + artist)), durationSec);
        }
        if (node == null) {
            node = pickBest(get("search?track_name=" + encode(title)), durationSec);
        }
        if (node == null) return null;
        return toLyrics(node);
    }

    @Nullable
    private JsonNode get(String pathAndQuery) throws IOException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(API_BASE + pathAndQuery))
                .header("User-Agent", USER_AGENT)
                .timeout(Duration.ofSeconds(10))
                .GET()
                .build();
        HttpResponse<String> response = send(request);
        if (response.statusCode() == 503 || response.statusCode() == 429) {
            sleepQuietly(1200);
            response = send(request);
        }
        if (response.statusCode() == 404) return null;
        if (response.statusCode() / 100 != 2) {
            throw new IOException("LRCLIB " + response.statusCode() + ": " + response.body());
        }
        JsonNode node = MAPPER.readTree(response.body());
        if (node.isArray() && node.isEmpty()) return null;
        return node;
    }

    private HttpResponse<String> send(HttpRequest request) throws IOException {
        try {
            return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("lyrics request interrupted", e);
        }
    }

    @Nullable
    private static JsonNode pickBest(@Nullable JsonNode results, long durationSec) {
        if (results == null || !results.isArray()) return null;
        JsonNode best = null;
        long bestDiff = Long.MAX_VALUE;
        for (JsonNode candidate : results) {
            boolean hasLyrics = !candidate.path("syncedLyrics").asText("").isBlank()
                    || !candidate.path("plainLyrics").asText("").isBlank();
            if (!hasLyrics) continue;
            long diff = Math.abs(candidate.path("duration").asLong(0) - durationSec);
            boolean synced = !candidate.path("syncedLyrics").asText("").isBlank();
            long score = diff - (synced ? 1 : 0);
            if (score < bestDiff) {
                bestDiff = score;
                best = candidate;
            }
        }
        if (best == null) return null;
        long diff = Math.abs(best.path("duration").asLong(0) - durationSec);
        if (durationSec > 0 && diff > DURATION_TOLERANCE_SEC) {
            LOGGER.debug("lyrics candidate rejected by duration: diff={}s title={}", diff, best.path("trackName").asText());
            return null;
        }
        return best;
    }

    private static Lyrics toLyrics(JsonNode node) {
        String plain = node.path("plainLyrics").asText(null);
        List<LyricLine> synced = parseLrc(node.path("syncedLyrics").asText(""));
        return new Lyrics(
                node.path("trackName").asText(""),
                node.path("artistName").asText(""),
                plain == null || plain.isBlank() ? null : plain,
                synced,
                node.path("instrumental").asBoolean(false)
        );
    }

    static List<LyricLine> parseLrc(String lrc) {
        List<LyricLine> lines = new ArrayList<>();
        if (lrc == null || lrc.isBlank()) return lines;
        for (String raw : lrc.split("\\r?\\n")) {
            Matcher matcher = LRC_LINE.matcher(raw.trim());
            if (!matcher.matches()) continue;
            long minutes = Long.parseLong(matcher.group(1));
            long seconds = Long.parseLong(matcher.group(2));
            String fraction = matcher.group(3);
            long millis = 0;
            if (fraction != null) {
                millis = Long.parseLong((fraction + "000").substring(0, 3));
            }
            lines.add(new LyricLine((minutes * 60 + seconds) * 1000 + millis, matcher.group(4).trim()));
        }
        lines.sort((a, b) -> Long.compare(a.timeMs(), b.timeMs()));
        return lines;
    }

    static String cleanTitle(String title) {
        if (title == null) return "";
        String cleaned = TITLE_NOISE.matcher(title).replaceAll("").trim();
        return cleaned.isBlank() ? title.trim() : cleaned;
    }

    static String firstArtist(@Nullable String author) {
        if (author == null) return "";
        String first = author.split("[,/&]")[0].trim();
        return first.replaceAll("(?i)\\s*-\\s*topic$", "").trim();
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static void sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
