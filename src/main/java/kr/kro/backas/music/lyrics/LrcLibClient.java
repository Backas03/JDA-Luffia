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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class LrcLibClient {

    private static final Logger LOGGER = LoggerFactory.getLogger(LrcLibClient.class);
    private static final String API_BASE = "https://lrclib.net/api/";
    private static final String USER_AGENT = "JDA-Luffia/1.0 (https://github.com/Backas03/JDA-Luffia)";
    private static final long DURATION_TOLERANCE_SEC = 5;
    private static final int RETRY_ATTEMPTS = 4;
    private static final long RETRY_BASE_DELAY_MS = 1000;
    private static final Pattern LRC_LINE = Pattern.compile("\\[(\\d{1,2}):(\\d{2})(?:[.:](\\d{1,3}))?](.*)");
    private static final Pattern TITLE_NOISE = Pattern.compile(
            "(?i)\\s*[\\[(【].*?(official|mv|m/v|music video|lyric|audio|visualizer|ver\\.?|version|remaster|가사|자막|한글|번역|공식|뮤직비디오|4k|8k|hd).*?[\\])】]\\s*|\\s*[|_]\\s*(mv|m/v|official.*)$");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final int CACHE_SIZE = 300;
    private static final Map<String, Optional<Lyrics>> CACHE = new LinkedHashMap<>(64, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Optional<Lyrics>> eldest) {
            return size() > CACHE_SIZE;
        }
    };
    private static final Map<String, Object> LOCKS = new ConcurrentHashMap<>();

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    @Nullable
    public Lyrics find(AudioTrackInfo info) throws IOException {
        String identifier = info.identifier == null || info.identifier.isBlank() ? info.title + "|" + info.author : info.identifier;
        Object lock = LOCKS.computeIfAbsent(identifier, k -> new Object());
        synchronized (lock) {
            try {
                Optional<Lyrics> cached;
                synchronized (CACHE) {
                    cached = CACHE.get(identifier);
                }
                if (cached != null) return cached.orElse(null);
                Lyrics found = lookup(info);
                if (found != null) {
                    synchronized (CACHE) {
                        CACHE.put(identifier, Optional.of(found));
                    }
                }
                return found;
            } finally {
                LOCKS.remove(identifier, lock);
            }
        }
    }

    @Nullable
    private Lyrics lookup(AudioTrackInfo info) throws IOException {
        String title = cleanTitle(info.title);
        String artist = firstArtist(info.author);
        long durationSec = info.length / 1000;
        List<String[]> candidates = candidatePairs(title, artist);
        JsonNode node = null;
        int attempts = 0;
        for (String[] candidate : candidates) {
            if (attempts++ >= 6) break;
            node = get("get?track_name=" + encode(candidate[1]) + "&artist_name=" + encode(candidate[0]) + "&duration=" + durationSec);
            if (node != null) break;
        }
        if (node == null) {
            attempts = 0;
            for (String[] candidate : candidates) {
                if (attempts++ >= 3) break;
                node = pickBest(get("search?q=" + encode(candidate[1] + " " + candidate[0])), durationSec);
                if (node != null) break;
            }
        }
        if (node == null) {
            node = pickBest(get("search?track_name=" + encode(title)), durationSec);
        }
        if (node == null) {
            for (String[] candidate : candidates) {
                if (candidate[1].equalsIgnoreCase(title)) continue;
                node = pickBest(get("search?track_name=" + encode(candidate[1])), durationSec);
                break;
            }
        }
        if (node == null) return null;
        return toLyrics(node);
    }

    private static final Pattern BRACKET_TITLE = Pattern.compile("^(.*?)[「『](.+?)[」』].*$");
    private static final Pattern QUOTED_TITLE = Pattern.compile("^(.*?)\\s+['‘\"](.+?)['’\"].*$");
    private static final Pattern TRAILING_PAREN = Pattern.compile("\\s*[\\[(（][^)\\]）]*[\\])）]");

    static List<String[]> candidatePairs(String title, String channelArtist) {
        List<String[]> pairs = new ArrayList<>();
        addPair(pairs, channelArtist, title);
        Matcher bracket = BRACKET_TITLE.matcher(title);
        if (bracket.matches() && !bracket.group(1).isBlank()) addPair(pairs, bracket.group(1), bracket.group(2));
        Matcher quoted = QUOTED_TITLE.matcher(title);
        if (quoted.matches() && !quoted.group(1).isBlank()) addPair(pairs, quoted.group(1), quoted.group(2));
        for (String separator : new String[]{" - ", " – ", " — ", " _ ", " / ", "/"}) {
            int index = title.indexOf(separator);
            if (index <= 0 || index + separator.length() >= title.length()) continue;
            String left = title.substring(0, index);
            String right = title.substring(index + separator.length());
            addPair(pairs, left, right);
            addPair(pairs, right, left);
        }
        return pairs;
    }

    private static void addPair(List<String[]> pairs, String artist, String trackTitle) {
        for (String candidateArtist : variants(firstArtist(artist))) {
            for (String candidateTitle : variants(trackTitle.trim())) {
                if (candidateArtist.isBlank() || candidateTitle.isBlank()) continue;
                if (containsPair(pairs, candidateArtist, candidateTitle)) continue;
                pairs.add(new String[]{candidateArtist, candidateTitle});
            }
        }
    }

    private static boolean containsPair(List<String[]> pairs, String artist, String title) {
        for (String[] pair : pairs) {
            if (pair[0].equalsIgnoreCase(artist) && pair[1].equalsIgnoreCase(title)) return true;
        }
        return false;
    }

    private static final Pattern FEAT_SUFFIX = Pattern.compile("(?i)\\s+(feat\\.?|ft\\.?)\\s.*$");

    private static String[] variants(String value) {
        String trimmed = value.trim();
        List<String> variants = new ArrayList<>();
        variants.add(trimmed);
        String parenStripped = TRAILING_PAREN.matcher(trimmed).replaceAll("").trim();
        if (!parenStripped.isBlank() && !variants.contains(parenStripped)) variants.add(parenStripped);
        String featStripped = FEAT_SUFFIX.matcher(parenStripped.isBlank() ? trimmed : parenStripped).replaceAll("").trim();
        if (!featStripped.isBlank() && !variants.contains(featStripped)) variants.add(featStripped);
        return variants.toArray(new String[0]);
    }

    @Nullable
    private JsonNode get(String pathAndQuery) throws IOException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(API_BASE + pathAndQuery))
                .header("User-Agent", USER_AGENT)
                .timeout(Duration.ofSeconds(10))
                .GET()
                .build();
        HttpResponse<String> response = send(request);
        for (int attempt = 1; attempt <= RETRY_ATTEMPTS && (response.statusCode() == 503 || response.statusCode() == 429); attempt++) {
            sleepQuietly(RETRY_BASE_DELAY_MS * attempt);
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
            if (score < bestDiff || (score == bestDiff && best != null
                    && candidate.path("id").asLong(Long.MAX_VALUE) < best.path("id").asLong(Long.MAX_VALUE))) {
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
