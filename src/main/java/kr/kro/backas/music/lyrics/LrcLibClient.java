package kr.kro.backas.music.lyrics;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import kr.kro.backas.music.cache.DiskCache;
import kr.kro.backas.music.lyrics.sync.LyricsAligner;
import kr.kro.backas.music.lyrics.sync.Romaji;
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
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
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
    private static final int MIN_KEYWORD_LENGTH = 4;
    private static final long ALTERNATIVE_TOLERANCE_SEC = 10;
    private static final int MAX_ALIASES = 3;
    private static final double MIN_TITLE_SIMILARITY = 0.5;
    private static final int RETRY_ATTEMPTS = 4;
    private static final long RETRY_BASE_DELAY_MS = 1000;
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(15);
    private static final int MAX_PARALLEL_EXACT = 6;
    private static final int MAX_PARALLEL_SEARCH = 3;
    private static final int TIMEOUT_RETRIES = 1;
    private static final int MAX_FAILED_REQUESTS = 2;
    static final long LOOKUP_BUDGET_MS = 20_000;
    private static final long MIN_RESOLVER_WAIT_MS = 3_000;
    private static final Pattern LRC_LINE = Pattern.compile("\\[(\\d{1,2}):(\\d{2})(?:[.:](\\d{1,3}))?](.*)");
    private static final Pattern LRC_OFFSET = Pattern.compile("\\[offset:\\s*([+-]?\\d+)\\s*]", Pattern.CASE_INSENSITIVE);
    private static final Pattern TOPIC_CHANNEL = Pattern.compile("(?i)\\s-\\s*topic$");
    private static final Pattern MORE_ARTISTS = Pattern.compile("(?i)\\s+and\\s+\\d+\\s+more$");
    private static final Pattern BRACKETED_NAME =Pattern.compile("^(.+?)\\s*[(（\\[]([^)）\\]]+)[)）\\]]$");
    private static final Pattern CHANNEL_SUFFIX = Pattern.compile(
            "(?i)(\\s*[-|]?\\s*(official(\\s+youtube)?(\\s+channel)?|vevo|topic|공식(\\s*채널)?|오피셜|公式(チャンネル)?|channel))+$");
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
        String identifier = identifierOf(info);
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
                Lyrics found = DiskCache.defaultCache().read(diskPathOf(identifier), Lyrics.class);
                if (found != null) {
                    synchronized (CACHE) {
                        CACHE.put(identifier, Optional.of(found));
                    }
                    return found;
                }
                found = lookup(info);
                if (found != null) remember(identifier, found);
                else MISSES.put(identifier, System.currentTimeMillis());
                return found;
            } finally {
                LOCKS.remove(identifier, lock);
            }
        }
    }

    private static String identifierOf(AudioTrackInfo info) {
        return info.identifier == null || info.identifier.isBlank() ? info.title + "|" + info.author : info.identifier;
    }

    private static String diskPathOf(String identifier) {
        return "lyrics-v5/" + TranslationJobs.sha256(identifier) + ".json";
    }

    private static void remember(String identifier, Lyrics lyrics) {
        synchronized (CACHE) {
            CACHE.put(identifier, Optional.of(lyrics));
        }
        MISSES.remove(identifier);
        DiskCache.defaultCache().write(diskPathOf(identifier), lyrics);
    }

    @Nullable
    private Lyrics lookup(AudioTrackInfo info) throws IOException {
        long startedAt = System.currentTimeMillis();
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
        AtomicReference<JsonNode> rejected = new AtomicReference<>();
        JsonNode node = firstSuccess(failures, exactPaths, resolved, found -> {
            if (!unreliableSync(found, durationSec) && !translated(found, info.title)) return found;
            rejected.compareAndSet(null, found);
            return null;
        });
        JsonNode unsynced = node == null || hasSynced(node) ? null : node;
        if (unsynced != null) node = null;
        if (node == null) {
            List<String[]> searched = candidates.subList(0, Math.min(MAX_PARALLEL_SEARCH, candidates.size()));
            List<String> searchPaths = new ArrayList<>();
            for (String[] candidate : searched) {
                searchPaths.add("search?track_name=" + encode(candidate[1]) + "&artist_name=" + encode(candidate[0]));
            }
            for (String[] candidate : searched) {
                searchPaths.add("search?q=" + encode(candidate[1] + " " + candidate[0]));
            }
            JsonNode best = pickBest(gather(failures, searchPaths, resolved), durationSec, info.title);
            if (best != null && !unreliableSync(best, durationSec) && !translated(best, info.title)) node = best;
            else if (best != null) rejected.compareAndSet(null, best);
        }
        if (node == null && !hasResult(resolved)) {
            List<String> aliasPaths = new ArrayList<>();
            for (String alias : artistAliases(failures, artist)) {
                aliasPaths.add("search?track_name=" + encode(title) + "&artist_name=" + encode(alias));
            }
            JsonNode best = pickBest(gather(failures, aliasPaths, resolved), durationSec, info.title);
            if (best != null && !unreliableSync(best, durationSec) && !translated(best, info.title)) node = best;
        }
        if (node == null && !hasResult(resolved) && hasKnownArtist(info)) {
            String keyword = keyword(title);
            if (keyword != null) {
                JsonNode byKeyword = tryGet(failures, "search?track_name=" + encode(keyword) + "&artist_name=" + encode(artist));
                node = pickBest(similarTitles(byArtist(byKeyword, info), title), durationSec, info.title);
            }
        }
        if (node == null && !hasResult(resolved)) {
            node = pickBest(byArtist(tryGet(failures, "search?track_name=" + encode(title)), info), durationSec, info.title);
        }
        if (node == null && !hasResult(resolved)) {
            for (String[] candidate : candidates) {
                if (candidate[1].equalsIgnoreCase(title)) continue;
                node = pickBest(byArtist(tryGet(failures, "search?track_name=" + encode(candidate[1])), info), durationSec, info.title);
                break;
            }
        }
        if (unsynced != null && (node == null || !hasSynced(node) || unreliableSync(node, durationSec) || translated(node, info.title))) {
            node = unsynced;
        }
        if (node != null) return usable(node, durationSec);
        Lyrics byResolver = resolved == null ? null : awaitResolved(resolved, startedAt, info);
        if (byResolver != null) return byResolver;
        JsonNode fallback = rejected.get();
        if (fallback != null) return usable(fallback, durationSec);
        if (failures.last != null) throw failures.last;
        return null;
    }

    @Nullable
    static Lyrics awaitResolved(CompletableFuture<Lyrics> resolved, long startedAt, AudioTrackInfo info) {
        long waitMs = Math.max(MIN_RESOLVER_WAIT_MS, LOOKUP_BUDGET_MS - (System.currentTimeMillis() - startedAt));
        try {
            return resolved.get(waitMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            LOGGER.info("ai song lookup for {} is still running after {}ms, keeping its answer for the next time", info.title,
                    System.currentTimeMillis() - startedAt);
            String identifier = identifierOf(info);
            resolved.thenAccept(late -> {
                if (late != null) remember(identifier, late);
            });
            return null;
        } catch (ExecutionException e) {
            LOGGER.warn("ai song lookup for {} failed", info.title, e.getCause() == null ? e : e.getCause());
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
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

    @Nullable
    private ArrayNode gather(Failures failures, List<String> paths, @Nullable CompletableFuture<Lyrics> resolved) throws IOException {
        if (paths.isEmpty() || hasResult(resolved)) return null;
        List<CompletableFuture<JsonNode>> futures = new ArrayList<>();
        for (String path : paths) {
            futures.add(CompletableFuture.supplyAsync(() -> {
                try {
                    return tryGet(failures, path);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }, TranslationJobs.EXECUTOR));
        }
        ArrayNode merged = MAPPER.createArrayNode();
        Set<Long> seen = new HashSet<>();
        IOException failure = null;
        for (CompletableFuture<JsonNode> future : futures) {
            try {
                JsonNode found = future.join();
                if (found == null || !found.isArray()) continue;
                for (JsonNode candidate : found) {
                    if (seen.add(candidate.path("id").asLong(-1))) merged.add(candidate);
                }
            } catch (CompletionException e) {
                if (e.getCause() instanceof UncheckedIOException unchecked) failure = unchecked.getCause();
                else if (e.getCause() instanceof RuntimeException runtime) throw runtime;
                else throw e;
            }
        }
        if (merged.isEmpty() && failure != null) throw failure;
        return merged;
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
            JsonNode node = exact == null || unreliableSync(exact, durationSec) || translated(exact, info.title) ? null : exact;
            boolean lengthDiffers = false;
            if (node == null) {
                JsonNode results = song.artist().isBlank() ? null
                        : tryGet(failures, "search?track_name=" + encode(song.title()) + "&artist_name=" + encode(song.artist()));
                if (results == null) results = tryGet(failures, "search?track_name=" + encode(song.title()));
                ArrayNode same = sameSong(results, song, matchKey(info.title + " " + info.author));
                node = pickBest(same, durationSec, info.title);
                if (node == null) {
                    node = withPlainFirst(same);
                    lengthDiffers = node != null;
                }
            }
            if (node == null) node = exact;
            if (node == null || translated(node, info.title)) return null;
            Lyrics lyrics = usable(node, durationSec);
            return lengthDiffers ? plainOnly(lyrics) : lyrics;
        } catch (IOException | RuntimeException e) {
            LOGGER.debug("lyrics lookup by resolved song failed for {}", info.title, e);
            return null;
        }
    }

    @Nullable
    public Lyrics alternative(AudioTrackInfo info, Lyrics current, String language) {
        long durationSec = info.length / 1000;
        Set<String> artists = new LinkedHashSet<>();
        artists.add(current.artistName());
        artists.addAll(channelNames(firstArtist(info.author)));
        Failures failures = new Failures();
        ArrayNode pool = MAPPER.createArrayNode();
        Set<Long> seen = new HashSet<>();
        for (String artist : artists) {
            if (artist == null || artist.isBlank()) continue;
            try {
                JsonNode found = tryGet(failures, "search?q=" + encode(artist));
                if (found == null || !found.isArray()) continue;
                for (JsonNode candidate : found) {
                    if (seen.add(candidate.path("id").asLong(-1))) pool.add(candidate);
                }
            } catch (IOException e) {
                LOGGER.debug("alternative lyrics search failed for {}", info.title, e);
            }
        }
        JsonNode best = pickAlternative(pool, current.artistName(), durationSec, language);
        return best == null ? null : usable(best, durationSec);
    }

    @Nullable
    static JsonNode pickAlternative(JsonNode results, String artistName, long durationSec, String language) {
        String artist = matchKey(artistName);
        JsonNode best = null;
        long bestScore = Long.MAX_VALUE;
        for (JsonNode candidate : results) {
            String candidateArtist = matchKey(candidate.path("artistName").asText(""));
            if (artist.isEmpty() || candidateArtist.isEmpty()) continue;
            if (!candidateArtist.contains(artist) && !(candidateArtist.length() >= MIN_ARTIST_KEY_LENGTH && artist.contains(candidateArtist))) continue;
            long diff = Math.abs(candidate.path("duration").asLong(0) - durationSec);
            if (durationSec > 0 && diff > ALTERNATIVE_TOLERANCE_SEC) continue;
            boolean synced = !candidate.path("syncedLyrics").asText("").isBlank();
            String text = synced ? candidate.path("syncedLyrics").asText("") : candidate.path("plainLyrics").asText("");
            if (text.isBlank()) continue;
            List<LyricLine> lines = synced ? parseLrc(text) : new ArrayList<>();
            if (!synced) for (String line : text.split("\\r?\\n")) lines.add(new LyricLine(0, line));
            if (!language.equals(LyricsAligner.languageHint(lines))) continue;
            long score = diff - (synced ? 1 : 0);
            if (score < bestScore) {
                bestScore = score;
                best = candidate;
            }
        }
        return best;
    }

    public void override(AudioTrackInfo info, Lyrics lyrics) {
        remember(identifierOf(info), lyrics);
    }

    @Nullable
    static JsonNode byArtist(@Nullable JsonNode results, AudioTrackInfo info) {
        if (results == null || !results.isArray()) return results;
        boolean known = hasKnownArtist(info);
        String artist = matchKey(firstArtist(info.author));
        String video = matchKey(info.title + " " + info.author);
        ArrayNode same = MAPPER.createArrayNode();
        for (JsonNode candidate : results) {
            String candidateArtist = matchKey(candidate.path("artistName").asText(""));
            if (candidateArtist.isEmpty()) continue;
            boolean related = !artist.isEmpty() && (candidateArtist.contains(artist)
                    || (candidateArtist.length() >= MIN_ARTIST_KEY_LENGTH && artist.contains(candidateArtist)));
            boolean named = !known && candidateArtist.length() >= MIN_ARTIST_KEY_LENGTH && video.contains(candidateArtist);
            if (related || named) same.add(candidate);
        }
        return same;
    }

    private List<String> artistAliases(Failures failures, String artist) throws IOException {
        String key = matchKey(artist);
        if (key.length() < MIN_ARTIST_KEY_LENGTH) return List.of();
        return aliasesIn(tryGet(failures, "search?q=" + encode(artist)), artist);
    }

    static List<String> aliasesIn(@Nullable JsonNode results, String artist) {
        List<String> aliases = new ArrayList<>();
        String key = matchKey(artist);
        if (results == null || !results.isArray() || key.isEmpty()) return aliases;
        for (JsonNode candidate : results) {
            String name = candidate.path("artistName").asText("");
            if (!matchKey(name).contains(key)) continue;
            for (String alias : artistNames(name)) {
                String aliasKey = matchKey(alias);
                if (aliasKey.isEmpty() || aliasKey.equals(key) || aliasKey.contains(key)) continue;
                if (aliases.stream().noneMatch(known -> matchKey(known).equals(aliasKey))) aliases.add(alias);
            }
            if (aliases.size() >= MAX_ALIASES) break;
        }
        return aliases.size() > MAX_ALIASES ? aliases.subList(0, MAX_ALIASES) : aliases;
    }

    static List<String> artistNames(String name) {
        List<String> names = new ArrayList<>();
        Matcher bracketed = BRACKETED_NAME.matcher(name.trim());
        if (bracketed.matches()) {
            names.addAll(channelNames(bracketed.group(1)));
            names.addAll(channelNames(bracketed.group(2)));
        } else {
            names.addAll(channelNames(name));
        }
        return names;
    }

    static List<String> channelNames(String channel) {
        List<String> names = new ArrayList<>();
        String cleaned = CHANNEL_SUFFIX.matcher(channel).replaceAll("").trim();
        if (cleaned.isBlank()) return names;
        names.add(cleaned);
        List<String> groups = new ArrayList<>();
        StringBuilder group = new StringBuilder();
        Boolean groupCjk = null;
        for (String word : cleaned.split("\\s+")) {
            boolean cjk = hasCjkOrHangul(word);
            if (groupCjk != null && cjk != groupCjk) {
                groups.add(group.toString());
                group.setLength(0);
            }
            if (!group.isEmpty()) group.append(' ');
            group.append(word);
            groupCjk = cjk;
        }
        if (!group.isEmpty()) groups.add(group.toString());
        if (groups.size() > 1) {
            for (String name : groups) {
                if (matchKey(name).length() >= 2 && !names.contains(name)) names.add(name);
            }
        }
        return names;
    }

    private static boolean hasCjkOrHangul(String text) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if ((c >= 0xAC00 && c <= 0xD7A3) || (c >= 0x3040 && c <= 0x30FF) || (c >= 0x4E00 && c <= 0x9FFF)) return true;
        }
        return false;
    }

    @Nullable
    static String keyword(String title) {
        String best = null;
        for (String word : title.split("[^A-Za-z0-9]+")) {
            if (word.length() >= MIN_KEYWORD_LENGTH && (best == null || word.length() > best.length())) best = word;
        }
        return best == null || best.equalsIgnoreCase(title.trim()) ? null : best;
    }

    static ArrayNode similarTitles(@Nullable JsonNode results, String title) {
        ArrayNode similar = MAPPER.createArrayNode();
        if (results == null || !results.isArray()) return similar;
        String ours = matchKey(TRAILING_PAREN.matcher(title).replaceAll(""));
        for (JsonNode candidate : results) {
            String theirs = matchKey(TRAILING_PAREN.matcher(candidate.path("trackName").asText("")).replaceAll(""));
            if (!ours.isEmpty() && !theirs.isEmpty() && LyricsAligner.similarity(ours, theirs) >= MIN_TITLE_SIMILARITY) similar.add(candidate);
        }
        return similar;
    }

    static boolean hasKnownArtist(AudioTrackInfo info) {
        return hasCatalogMetadata(info) || (info.author != null && TOPIC_CHANNEL.matcher(info.author).find());
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
    private static final Pattern ALTERNATIVE_TITLE = Pattern.compile("^(.+?)\\s*[(（]\\s*([A-Za-z][^)）]*[A-Za-z])\\s*[)）]\\s*$");
    private static final Pattern ALTERNATIVE_NOISE = Pattern.compile("(?i)\\b(feat|ft|prod|ver|version|remix|mix|edit|cover|live|size|inst|instrumental|acoustic)\\b");
    private static final Pattern TRAILING_PAREN = Pattern.compile("\\s*[\\[(（][^)\\]）]*[\\])）]");

    static List<String[]> candidatePairs(String title, String channelArtist) {
        List<String[]> pairs = new ArrayList<>();
        addPair(pairs, channelArtist, title);
        for (String name : channelNames(channelArtist)) addPair(pairs, name, title);
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
        for (String[] pair : new ArrayList<>(pairs)) {
            Matcher alternative = ALTERNATIVE_TITLE.matcher(pair[1]);
            if (!alternative.matches() || !hasCjk(alternative.group(1)) || ALTERNATIVE_NOISE.matcher(alternative.group(2)).find()) continue;
            if (!containsPair(pairs, pair[0], alternative.group(2).trim())) pairs.add(new String[]{pair[0], alternative.group(2).trim()});
        }
        String artistKey = matchKey(channelArtist);
        List<String[]> ordered = new ArrayList<>();
        for (String[] pair : pairs) {
            if (!artistKey.isEmpty() && matchKey(pair[0]).equals(artistKey) && !matchKey(pair[1]).contains(artistKey)) ordered.add(pair);
        }
        for (String[] pair : pairs) {
            if (!ordered.contains(pair)) ordered.add(pair);
        }
        return ordered;
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
        return pickBest(results, durationSec, null);
    }

    @Nullable
    static JsonNode pickBest(@Nullable JsonNode results, long durationSec, @Nullable String title) {
        if (results == null || !results.isArray()) return null;
        JsonNode best = null;
        long bestDiff = Long.MAX_VALUE;
        for (JsonNode candidate : results) {
            boolean hasLyrics = !candidate.path("syncedLyrics").asText("").isBlank()
                    || !candidate.path("plainLyrics").asText("").isBlank();
            if (!hasLyrics) continue;
            long diff = Math.abs(candidate.path("duration").asLong(0) - durationSec);
            boolean synced = !candidate.path("syncedLyrics").asText("").isBlank();
            long score = diff - (synced ? 1 : 0) + (synced && unreliableSync(candidate, durationSec) ? TRUNCATED_PENALTY : 0)
                    + (translated(candidate, title) ? TRUNCATED_PENALTY : 0);
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

    private static boolean hasSynced(JsonNode candidate) {
        return !candidate.path("syncedLyrics").asText("").isBlank();
    }

    static boolean translated(JsonNode candidate, @Nullable String title) {
        if (title == null || !hasCjk(title)) return false;
        String text = candidate.path("syncedLyrics").asText("");
        if (text.isBlank()) text = candidate.path("plainLyrics").asText("");
        if (text.isBlank() || hasCjk(text)) return false;
        List<String> lines = new ArrayList<>();
        for (LyricLine line : parseLrc(text)) lines.add(line.text());
        if (lines.isEmpty()) lines = List.of(text.split("\\r?\\n"));
        return !Romaji.looksLike(lines);
    }

    private static boolean hasCjk(String text) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if ((c >= 0x3040 && c <= 0x30FF) || (c >= 0x4E00 && c <= 0x9FFF)) return true;
        }
        return false;
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
        String first = MORE_ARTISTS.matcher(author.split("[,/&]")[0].trim()).replaceAll("").trim();
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
