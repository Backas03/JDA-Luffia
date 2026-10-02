package kr.kro.backas.music.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
import java.text.Normalizer;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

public class SongInfoClient {

    private static final Logger LOGGER = LoggerFactory.getLogger(SongInfoClient.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String USER_AGENT = "Luffia-Discord-Bot/3 (song info lookup)";
    private static final String CATALOG_URL = "https://itunes.apple.com/search?entity=song&limit=8&country=%s&term=%s";
    private static final String WIKI_URL = "https://%s.wikipedia.org/w/api.php?action=query&format=json&redirects=1"
            + "&generator=search&gsrlimit=%d&gsrsearch=%s&prop=extracts&exintro=1&explaintext=1&exlimit=%d&exchars=%d";
    private static final String WIKI_ARTICLE_URL = "https://%s.wikipedia.org/w/api.php?action=query&format=json"
            + "&prop=extracts&explaintext=1&exchars=%d&pageids=%d";
    private static final int WIKI_EXTRACT_CHARS = 600;
    private static final int WIKI_ARTICLE_CHARS = 1500;
    private static final int MAX_WIKI_PAGES = 3;
    private static final int MAX_LYRICS_CHARS = 1000;
    private static final int CACHE_SIZE = 100;
    private static final long RATE_LIMIT_RETRY_MS = 1500;

    public record Catalog(String track, String artist, String album, String released, String genre) {
    }

    public record WikiPage(String language, long pageId, String title, String extract) {
    }

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
    private final Map<String, ObjectNode> cache = Collections.synchronizedMap(new LinkedHashMap<>(32, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, ObjectNode> eldest) {
            return size() > CACHE_SIZE;
        }
    });

    public ObjectNode describe(String title, String artist, @Nullable String lyrics) {
        String key = title + "\n" + artist;
        ObjectNode cached = cache.get(key);
        if (cached == null) {
            AtomicBoolean complete = new AtomicBoolean(true);
            cached = lookup(title, artist, complete);
            if (complete.get()) cache.put(key, cached);
        }
        ObjectNode out = cached.deepCopy();
        if (lyrics != null && !lyrics.isBlank()) {
            String text = lyrics.strip();
            out.put("lyrics", PromptSafe.data(text.length() > MAX_LYRICS_CHARS ? text.substring(0, MAX_LYRICS_CHARS) : text));
        }
        return out;
    }

    private ObjectNode lookup(String title, String artist, AtomicBoolean complete) {
        ObjectNode out = MAPPER.createObjectNode();
        String script = TrackHints.script(title + " " + artist);
        Catalog catalog = null;
        for (String country : catalogCountries(script)) {
            try {
                catalog = catalog(title, artist, country);
            } catch (IOException e) {
                complete.set(false);
                LOGGER.debug("catalog lookup failed for {} ({})", title, country, e);
            }
            if (catalog != null) break;
        }
        if (catalog != null) {
            ObjectNode node = out.putObject("catalog");
            node.put("track", TrackHints.field(catalog.track()));
            node.put("artist", TrackHints.field(catalog.artist()));
            if (!catalog.album().isBlank()) node.put("album", TrackHints.field(catalog.album()));
            if (!catalog.released().isBlank()) node.put("released", catalog.released());
            if (!catalog.genre().isBlank()) node.put("genre", TrackHints.field(catalog.genre()));
        }
        List<WikiPage> pages = new ArrayList<>();
        for (String language : wikiLanguages(script)) {
            if (!pages.isEmpty()) break;
            try {
                addPages(pages, wiki(language, title + " " + artist, 2), title, artist);
                if (!artist.isBlank()) addPages(pages, wiki(language, artist, 1), title, artist);
            } catch (IOException e) {
                complete.set(false);
                LOGGER.debug("wiki lookup failed for {} ({})", title, language, e);
            }
        }
        WikiPage main = mainPage(pages, title, artist);
        ArrayNode wiki = out.putArray("reference");
        for (WikiPage page : pages) {
            String text = page.extract();
            if (page == main) {
                try {
                    String longer = article(page);
                    if (longer.length() > text.length()) text = longer;
                } catch (IOException e) {
                    complete.set(false);
                    LOGGER.debug("wiki article lookup failed for {}", page.title(), e);
                }
            }
            wiki.addObject()
                    .put("source", page.language() + ".wikipedia.org")
                    .put("page", TrackHints.field(page.title()))
                    .put("text", PromptSafe.data(text));
        }
        return out;
    }

    @Nullable
    static WikiPage mainPage(List<WikiPage> pages, String title, String artist) {
        String normalizedTitle = normalize(title);
        String normalizedArtist = normalize(artist);
        for (WikiPage page : pages) {
            if (normalizedTitle.length() >= 3 && normalize(page.title()).contains(normalizedTitle)) return page;
        }
        for (WikiPage page : pages) {
            if (normalizedArtist.length() >= 2 && normalize(page.title()).contains(normalizedArtist)) return page;
        }
        return pages.isEmpty() ? null : pages.get(0);
    }

    private String article(WikiPage page) throws IOException {
        JsonNode pages = get(String.format(WIKI_ARTICLE_URL, page.language(), WIKI_ARTICLE_CHARS, page.pageId()))
                .path("query").path("pages");
        for (JsonNode node : pages) {
            return node.path("extract").asText("").replaceAll("\\s+", " ").strip();
        }
        return "";
    }

    private static void addPages(List<WikiPage> pages, List<WikiPage> found, String title, String artist) {
        for (WikiPage page : found) {
            if (pages.size() >= MAX_WIKI_PAGES) return;
            if (!isRelevant(page, title, artist)) continue;
            boolean duplicate = false;
            for (WikiPage existing : pages) {
                if (existing.language().equals(page.language()) && existing.title().equals(page.title())) duplicate = true;
            }
            if (!duplicate) pages.add(page);
        }
    }

    static boolean isRelevant(WikiPage page, String title, String artist) {
        String pageTitle = normalize(page.title());
        String text = normalize(page.extract());
        String normalizedTitle = normalize(title);
        String normalizedArtist = normalize(artist);
        boolean hasTitle = normalizedTitle.length() >= 3;
        boolean hasArtist = normalizedArtist.length() >= 2;
        if (hasTitle && pageTitle.contains(normalizedTitle)) return !hasArtist || text.contains(normalizedArtist) || pageTitle.contains(normalizedArtist);
        if (hasArtist && pageTitle.contains(normalizedArtist)) return true;
        return hasTitle && hasArtist && text.contains(normalizedTitle) && text.contains(normalizedArtist);
    }

    static List<String> catalogCountries(String script) {
        if (script.contains("kana")) return List.of("jp", "us");
        if (script.contains("hangul")) return List.of("kr", "us");
        if (script.contains("han")) return List.of("jp", "us");
        return List.of("us", "jp");
    }

    static List<String> wikiLanguages(String script) {
        if (script.contains("kana")) return List.of("ja", "ko");
        if (script.contains("hangul")) return List.of("ko", "en");
        if (script.contains("han")) return List.of("ja", "zh", "ko");
        return List.of("en", "ko");
    }

    @Nullable
    Catalog catalog(String title, String artist, String country) throws IOException {
        JsonNode results = get(String.format(CATALOG_URL, country, encode((artist + " " + title).strip()))).path("results");
        return pickCatalog(results, title, artist);
    }

    @Nullable
    static Catalog pickCatalog(JsonNode results, String title, String artist) {
        String normalizedTitle = normalize(title);
        String normalizedArtist = normalize(artist);
        if (normalizedTitle.isEmpty()) return null;
        Catalog exact = null;
        Catalog variant = null;
        for (JsonNode result : results) {
            String track = result.path("trackName").asText("");
            String trackArtist = result.path("artistName").asText("");
            String normalizedTrack = normalize(track);
            String normalizedTrackArtist = normalize(trackArtist);
            if (normalizedTrack.isEmpty() || !normalizedTrack.startsWith(normalizedTitle)) continue;
            if (!normalizedArtist.isEmpty() && (normalizedTrackArtist.isEmpty()
                    || (!normalizedTrackArtist.contains(normalizedArtist) && !normalizedArtist.contains(normalizedTrackArtist)))) {
                continue;
            }
            String released = result.path("releaseDate").asText("");
            Catalog catalog = new Catalog(track, trackArtist, result.path("collectionName").asText(""),
                    released.length() >= 10 ? released.substring(0, 10) : released, result.path("primaryGenreName").asText(""));
            if (normalizedTrack.equals(normalizedTitle)) {
                if (exact == null || isEarlier(catalog, exact)) exact = catalog;
            } else if (variant == null || isEarlier(catalog, variant)) {
                variant = catalog;
            }
        }
        return exact != null ? exact : variant;
    }

    private static boolean isEarlier(Catalog candidate, Catalog current) {
        return !candidate.released().isBlank() && (current.released().isBlank() || candidate.released().compareTo(current.released()) < 0);
    }

    List<WikiPage> wiki(String language, String query, int limit) throws IOException {
        JsonNode pages = get(String.format(WIKI_URL, language, limit, encode(query.strip()), limit, WIKI_EXTRACT_CHARS))
                .path("query").path("pages");
        List<JsonNode> ordered = new ArrayList<>();
        pages.forEach(ordered::add);
        ordered.sort(Comparator.comparingInt(node -> node.path("index").asInt(Integer.MAX_VALUE)));
        List<WikiPage> found = new ArrayList<>();
        for (JsonNode page : ordered) {
            String extract = page.path("extract").asText("").replaceAll("\\s+", " ").strip();
            if (extract.isEmpty()) continue;
            found.add(new WikiPage(language, page.path("pageid").asLong(0), page.path("title").asText(""), extract));
        }
        return found;
    }

    private JsonNode get(String url) throws IOException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(8))
                .header("User-Agent", USER_AGENT)
                .GET()
                .build();
        HttpResponse<String> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 429) {
                Thread.sleep(RATE_LIMIT_RETRY_MS);
                response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("song info request interrupted", e);
        }
        if (response.statusCode() / 100 != 2) throw new IOException("song info " + response.statusCode());
        return MAPPER.readTree(response.body());
    }

    static String normalize(@Nullable String text) {
        if (text == null) return "";
        String normalized = Normalizer.normalize(text, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < normalized.length(); i++) {
            char c = normalized.charAt(i);
            if (Character.isLetterOrDigit(c)) out.append(c);
        }
        return out.toString();
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
