package kr.kro.backas.music.lyrics;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class TranslationClient {

    private static final Logger LOGGER = LoggerFactory.getLogger(TranslationClient.class);
    private static final int CACHE_SIZE = 200;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String baseUrl;
    private final HttpClient httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    private final Map<String, Map<Integer, String>> cache = Collections.synchronizedMap(
            new LinkedHashMap<>(64, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Map<Integer, String>> eldest) {
                    return size() > CACHE_SIZE;
                }
            });

    public TranslationClient(@Nullable String baseUrl) {
        this.baseUrl = baseUrl == null || baseUrl.isBlank() ? null : baseUrl.replaceAll("/+$", "");
    }

    public boolean isEnabled() {
        return baseUrl != null;
    }

    public Map<Integer, String> cacheFor(String trackKey) {
        return cache.computeIfAbsent(trackKey, k -> new ConcurrentHashMap<>());
    }

    public List<String> translate(String sourceLanguage, List<String> lines) throws IOException {
        if (baseUrl == null) throw new IOException("translator disabled");
        ObjectNode body = MAPPER.createObjectNode();
        body.put("src", sourceLanguage);
        body.put("tgt", LyricsLanguage.KOREAN);
        ArrayNode array = body.putArray("lines");
        lines.forEach(array::add);
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/translate"))
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(60))
                .POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(body)))
                .build();
        HttpResponse<String> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("translation interrupted", e);
        }
        if (response.statusCode() / 100 != 2) {
            throw new IOException("translator " + response.statusCode() + ": " + response.body());
        }
        JsonNode node = MAPPER.readTree(response.body());
        List<String> result = new ArrayList<>();
        for (JsonNode line : node.path("lines")) {
            result.add(line.asText(""));
        }
        if (result.size() != lines.size()) {
            LOGGER.warn("translator returned {} lines for {} inputs", result.size(), lines.size());
            throw new IOException("translator line count mismatch");
        }
        return result;
    }

    public boolean isHealthy() {
        if (baseUrl == null) return false;
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/health"))
                    .timeout(Duration.ofSeconds(3))
                    .GET()
                    .build();
            return httpClient.send(request, HttpResponse.BodyHandlers.ofString()).statusCode() == 200;
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            return false;
        }
    }
}
