package kr.kro.backas.music.lyrics;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import kr.kro.backas.music.cache.DiskCache;
import com.sedmelluq.discord.lavaplayer.track.AudioTrackInfo;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletionException;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class LrcLibClient {

    private static final Logger LOGGER = LoggerFactory.getLogger(LrcLibClient.class);
    private static final String API_BASE = "https://lrclib.net/api/";
    private static final String USER_AGENT = "JDA-Luffia/1.0 (https://github.com/Backas03/JDA-Luffia)";
    private static final long DURATION_TOLERANCE_SEC = 5;
    private static final double MIN_SYNCED_COVERAGE = 0.6;
    private static final long TRUNCATED_PENALTY = DURATION_TOLERANCE_SEC + 2;
    private static final int MIN_CROWDED_LINES = 10;
    private static final long MIN_LINE_GAP_MS = 1000;
    private static final int MIN_ARTIST_KEY_LENGTH = 3;
    private static final int RETRY_ATTEMPTS = 4;
    private static final long RETRY_BASE_DELAY_MS = 1000;
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(15);
    private static final int MAX_PARALLEL_EXACT = 6;
    private static final int MAX_PARALLEL_SEARCH = 3;
    private static final int TIMEOUT_RETRIES = 1;
    private static final int MAX_FAILED_REQUESTS = 2;
    private static final Pattern LRC_LINE = Pattern.compile("\\[(\\d{1,2}):(\\d{2})(?:[.:](\\d{1,3}))?](.*)");
    private static final Pattern LRC_OFFSET = Pattern.compile("\\[offset:\\s*([+-]?\\d+)\\s*]", Pattern.CASE_INSENSITIVE);
    private static final Pattern TITLE_NOISE = Pattern.compile(
            "(?i)\\s*[\\[(【].*?(official|mv|m/v|music video|lyric|audio|visualizer|ver\\.?|version|remaster|가사|자막|한글|번역|공식|뮤직비디오|4k|8k|hd).*?[\\])】]\\s*|\\s*[\\[(【]\\s*from\\s.*?[\\])】]\\s*|\\s*[|_]\\s*(mv|m/v|official.*)$");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final int CACHE_SIZE = 300;
    private static final Map<String, Optional<Lyrics>> CACHE = new LinkedHashMap<>(64, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Optional<Lyrics>> eldest) {
            return size() > CACHE_SIZE;
        }
    };
    private static final Map<String, Object> LOCKS = new ConcurrentHashMap<>();
    private static final long MISS_TTL_MS = 10 * 60 * 1000;
    private static final int MISS_CACHE_SIZE = 500;
    private static final Map<String, Long> MISSES = Collections.synchronizedMap(new LinkedHashMap<>(64, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Long> eldest) {
            return size() > MISS_CACHE_SIZE;
        }
    });

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();
    @Nullable
    private final SongResolver resolver;

    public LrcLibClient() {
        this(null);
    }

    public LrcLibClient(@Nullable SongResolver resolver) {
        this.resolver = resolver;
    }

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
                Long missedAt = MISSES.get(identifier);
                if (missedAt != null && System.currentTimeMillis() - missedAt < MISS_TTL_MS) return null;
                String diskPath = "lyrics-v3/" + TranslationJobs.sha256(identifier) + ".json";
                Lyrics found = DiskCache.defaultCache().read(diskPath, Lyrics.class);
                boolean fromDisk = found != null;
                if (!fromDisk) found = lookup(info);
                if (found != null) {
                    synchronized (CACHE) {
                        CACHE.put(identifier, Optional.of(found));
                    }
                    if (!fromDisk) DiskCache.defaultCache().write(diskPath, found);
                } else {
                    MISSES.put(identifier, System.currentTimeMillis());
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
        CompletableFuture<Lyrics> resolved = resolver == null || hasCatalogMetadata(info) ? null
                : CompletableFuture.supplyAsync(() -> lookupResolved(info), TranslationJobs.EXECUTOR);
        Failures failures = new Failures();
        List<String> exactPaths = new ArrayList<>();
        for (String[] candidate : candidates) {
            if (exactPaths.size() >= MAX_PARALLEL_EXACT) break;
            exactPaths.add("get?track_name=" + encode(candidate[1]) + "&artist_name=" + encode(candidate[0]) + "&duration=" + durationSec);
        }
        JsonNode exact = firstSuccess(failures, exactPaths, resolved, found -> found);
        JsonNode node = exact == null || unreliableSync(exact, durationSec) ? null : exact;
        if (node == null) {
            List<String> searchPaths = new ArrayList<>();
            for (String[] candidate : candidates) {
                if (searchPaths.size() >= MAX_PARALLEL_SEARCH) break;
                searchPaths.add("search?q=" + encode(candidate[1] + " " + candidate[0]));
            }
            node = firstSuccess(failures, searchPaths, resolved, found -> pickBest(found, durationSec));
        }
        if (node == null && !hasResult(resolved)) {
            node = pickBest(tryGet(failures, "search?track_name=" + encode(title)), durationSec);
        }
        if (node == null && !hasResult(resolved)) {
            for (String[] candidate : candidates) {
                if (candidate[1].equalsIgnoreCase(title)) continue;
                node = pickBest(tryGet(failures, "search?track_name=" + encode(candidate[1])), durationSec);
                break;
            }
        }
        if (node != null) return usable(node, durationSec);
        Lyrics byResolver = resolved == null ? null : resolved.join();
        if (byResolver != null) return byResolver;
        if (exact != null) return usable(exact, durationSec);
        if (failures.last != null) throw failures.last;
        return null;
    }

    @Nullable
    private JsonNode firstSuccess(Failures failures, List<String> paths, @Nullable CompletableFuture<Lyrics> resolved,
                                  Function<JsonNode, JsonNode> select) throws IOException {
        if (paths.isEmpty() || hasResult(resolved)) return null;
        List<CompletableFuture<JsonNode>> futures = new ArrayList<>();
        for (String path : paths) {
            futures.add(CompletableFuture.supplyAsync(() -> {
                try {
                    JsonNode found = tryGet(failures, path);
                    return found == null ? null : select.apply(found);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }, TranslationJobs.EXECUTOR));
        }
        IOException failure = null;
        JsonNode best = null;
        for (CompletableFuture<JsonNode> future : futures) {
            if (best != null) break;
            try {
                best = future.join();
            } catch (CompletionException e) {
                if (e.getCause() instanceof UncheckedIOException unchecked) failure = unchecked.getCause();
                else if (e.getCause() instanceof RuntimeException runtime) throw runtime;
                else throw e;
            }
        }
        if (best == null && failure != null) throw failure;
        return best;
    }

    private static boolean hasResult(@Nullable CompletableFuture<Lyrics> resolved) {
        return resolved != null && resolved.isDone() && resolved.getNow(null) != null;
    }

    @Nullable
    private Lyrics lookupResolved(AudioTrackInfo info) {
        try {
            SongResolver.Song song = resolver.resolve(info);
            if (song == null) return null;
            long durationSec = info.length / 1000;
            Failures failures = new Failures();
            JsonNode exact = song.artist().isBlank() ? null
                    : tryGet(failures, "get?track_name=" + encode(song.title()) + "&artist_name=" + encode(song.artist()) + "&duration=" + durationSec);
            JsonNode node = exact == null || unreliableSync(exact, durationSec) ? null : exact;
            boolean lengthDiffers = false;
            if (node == null) {
                JsonNode results = song.artist().isBlank() ? null
                        : tryGet(failures, "search?track_name=" + encode(song.title()) + "&artist_name=" + encode(song.artist()));
                if (results == null) results = tryGet(failures, "search?track_name=" + encode(song.title()));
                ArrayNode same = sameSong(results, song, matchKey(info.title + " " + info.author));
                node = pickBest(same, durationSec);
                if (node == null) {
                    node = withPlainFirst(same);
                    lengthDiffers = node != null;
                }
            }
            if (node == null) node = exact;
            if (node == null) return null;
            Lyrics lyrics = usable(node, durationSec);
            return lengthDiffers ? plainOnly(lyrics) : lyrics;
        } catch (IOException | RuntimeException e) {
            LOGGER.debug("lyrics lookup by resolved song failed for {}", info.title, e);
            return null;
        }
    }

    private static boolean hasCatalogMetadata(AudioTrackInfo info) {
        return info.uri != null && info.uri.contains("open.spotify.com");
    }

    static ArrayNode sameSong(@Nullable JsonNode results, SongResolver.Song song, String videoKey) {
        ArrayNode same = MAPPER.createArrayNode();
        if (results == null || !results.isArray()) return same;
        String title = matchKey(song.title());
        String artist = matchKey(song.artist());
        if (title.isEmpty()) return same;
        for (JsonNode candidate : results) {
            if (candidate.path("plainLyrics").asText("").isBlank() && candidate.path("syncedLyrics").asText("").isBlank()) continue;
            if (!matchKey(candidate.path("trackName").asText("")).equals(title)) continue;
            String candidateArtist = matchKey(candidate.path("artistName").asText(""));
            if (candidateArtist.isEmpty()) continue;
            boolean namedByAi = !artist.isEmpty() && (candidateArtist.contains(artist) || artist.contains(candidateArtist));
            boolean namedInVideo = candidateArtist.length() >= MIN_ARTIST_KEY_LENGTH && videoKey.contains(candidateArtist);
            if (namedByAi || namedInVideo) same.add(candidate);
        }
        return same;
    }

    @Nullable
    static JsonNode withPlainFirst(ArrayNode candidates) {
        JsonNode best = null;
        for (JsonNode candidate : candidates) {
            if (best == null || (best.path("plainLyrics").asText("").isBlank() && !candidate.path("plainLyrics").asText("").isBlank())) {
                best = candidate;
            }
        }
        return best;
    }

    static Lyrics plainOnly(Lyrics lyrics) {
        String plain = lyrics.plain();
        if ((plain == null || plain.isBlank()) && lyrics.hasSynced()) {
            StringBuilder text = new StringBuilder();
            for (LyricLine line : lyrics.synced()) text.append(line.text()).append('\n');
            plain = text.toString().strip();
        }
        return new Lyrics(lyrics.trackName(), lyrics.artistName(), plain == null || plain.isBlank() ? null : plain, List.of(),
                lyrics.instrumental());
    }

    static String matchKey(@Nullable String text) {
        if (text == null) return "";
        String normalized = java.text.Normalizer.normalize(text, java.text.Normalizer.Form.NFKC).toLowerCase(java.util.Locale.ROOT);
        StringBuilder key = new StringBuilder();
        for (int i = 0; i < normalized.length(); i++) {
            char c = normalized.charAt(i);
            if (Character.isLetterOrDigit(c)) key.append(c);
        }
        return key.toString();
    }

    private static final class Failures {
        private int count;
        private IOException last;
    }

    @Nullable
    private JsonNode tryGet(Failures failures, String pathAndQuery) throws IOException {
        try {
            return get(pathAndQuery);
        } catch (IOException e) {
            if (Thread.currentThread().isInterrupted()) throw e;
            synchronized (failures) {
                failures.last = e;
                if (++failures.count >= MAX_FAILED_REQUESTS) throw e;
            }
            LOGGER.info("LRCLIB request failed, trying the next candidate: {}", e.toString());
            return null;
        }
    }

    private static final Pattern BRACKET_TITLE = Pattern.compile("^(.*?)[「『](.+?)[」』].*$");
    private static final Pattern QUOTED_TITLE = Pattern.compile("^(.*?)\\s+['‘\"](.+?)['’\"].*$");
    private static final Pattern TRAILING_PAREN = Pattern.compile("\\s*[\\[(（][^)\\]）]*[\\])）]");

    static List<String[]> candidatePairs(String title, String channelArtist) {
        List<String[]> pairs = new ArrayList<>();
        addPair(pairs, channelArtist, title);
        Matcher bracket = BRACKET_TITLE.matcher(title);
        if (bracket.matches()) addPair(pairs, bracket.group(1).isBlank() ? channelArtist : bracket.group(1), bracket.group(2));
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
                .timeout(REQUEST_TIMEOUT)
                .GET()
                .build();
        HttpResponse<String> response = sendRetryingTimeouts(request);
        for (int attempt = 1; attempt <= RETRY_ATTEMPTS && (response.statusCode() == 503 || response.statusCode() == 429); attempt++) {
            sleepQuietly(RETRY_BASE_DELAY_MS * attempt);
            response = sendRetryingTimeouts(request);
        }
        if (response.statusCode() == 404) return null;
        if (response.statusCode() / 100 != 2) {
            throw new IOException("LRCLIB " + response.statusCode() + ": " + response.body());
        }
        JsonNode node = MAPPER.readTree(response.body());
        if (node.isArray() && node.isEmpty()) return null;
        return node;
    }

    private HttpResponse<String> sendRetryingTimeouts(HttpRequest request) throws IOException {
        for (int attempt = 0; ; attempt++) {
            try {
                return send(request);
            } catch (HttpTimeoutException e) {
                if (attempt >= TIMEOUT_RETRIES) throw e;
                LOGGER.info("LRCLIB request timed out, retrying: {}", request.uri().getPath());
            }
        }
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
    static JsonNode pickBest(@Nullable JsonNode results, long durationSec) {
        if (results == null || !results.isArray()) return null;
        JsonNode best = null;
        long bestDiff = Long.MAX_VALUE;
        for (JsonNode candidate : results) {
            boolean hasLyrics = !candidate.path("syncedLyrics").asText("").isBlank()
                    || !candidate.path("plainLyrics").asText("").isBlank();
            if (!hasLyrics) continue;
            long diff = Math.abs(candidate.path("duration").asLong(0) - durationSec);
            boolean synced = !candidate.path("syncedLyrics").asText("").isBlank();
            long score = diff - (synced ? 1 : 0) + (synced && unreliableSync(candidate, durationSec) ? TRUNCATED_PENALTY : 0);
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

    static boolean unreliableSync(JsonNode candidate, long durationSec) {
        List<LyricLine> lines = parseLrc(candidate.path("syncedLyrics").asText(""));
        if (lines.isEmpty()) return false;
        long last = lines.get(lines.size() - 1).timeMs();
        if (durationSec > 0 && last < durationSec * 1000 * MIN_SYNCED_COVERAGE) return true;
        int sung = 0;
        long firstSung = 0;
        long lastSung = 0;
        for (LyricLine line : lines) {
            if (line.text().isBlank()) continue;
            if (sung++ == 0) firstSung = line.timeMs();
            lastSung = line.timeMs();
        }
        return sung >= MIN_CROWDED_LINES && lastSung - firstSung < sung * MIN_LINE_GAP_MS;
    }

    private static Lyrics usable(JsonNode node, long durationSec) {
        Lyrics lyrics = toLyrics(node);
        return unreliableSync(node, durationSec) ? plainOnly(lyrics) : lyrics;
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

    private static final Pattern CREDIT_LINE = Pattern.compile("^\\s*(作词|作詞|作曲|编曲|編曲|词|曲)\\s*[:：].*$");

    static List<LyricLine> parseLrc(String lrc) {
        List<LyricLine> lines = new ArrayList<>();
        if (lrc == null || lrc.isBlank()) return lines;
        long offsetMs = lrcOffsetMs(lrc);
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
            String text = matcher.group(4).trim();
            if (CREDIT_LINE.matcher(text).matches()) continue;
            lines.add(new LyricLine(Math.max(0, (minutes * 60 + seconds) * 1000 + millis - offsetMs), text));
        }
        lines.sort((a, b) -> Long.compare(a.timeMs(), b.timeMs()));
        return dropDuplicateTimestampTranslations(lines);
    }

    static long lrcOffsetMs(String lrc) {
        Matcher matcher = LRC_OFFSET.matcher(lrc);
        return matcher.find() ? Long.parseLong(matcher.group(1)) : 0;
    }

    private static List<LyricLine> dropDuplicateTimestampTranslations(List<LyricLine> lines) {
        List<LyricLine> filtered = new ArrayList<>(lines.size());
        int i = 0;
        while (i < lines.size()) {
            int j = i;
            while (j < lines.size() && lines.get(j).timeMs() == lines.get(i).timeMs()) j++;
            LyricLine chosen = lines.get(i);
            if (j - i > 1) {
                for (int k = i; k < j; k++) {
                    if (containsOriginalScript(lines.get(k).text())) {
                        chosen = lines.get(k);
                        break;
                    }
                }
            }
            filtered.add(chosen);
            i = j;
        }
        return filtered;
    }

    private static boolean containsOriginalScript(@Nullable String text) {
        if (text == null) return false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if ((c >= 0x3040 && c <= 0x30FF) || (c >= 0x4E00 && c <= 0x9FFF)
                    || (c >= 0xAC00 && c <= 0xD7A3) || (c >= 0x0400 && c <= 0x04FF)) {
                return true;
            }
        }
        return false;
    }

    public static String cleanTitle(String title) {
        if (title == null) return "";
        String cleaned = TITLE_NOISE.matcher(title).replaceAll("").trim();
        return cleaned.isBlank() ? title.trim() : cleaned;
    }

    public static String firstArtist(@Nullable String author) {
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
