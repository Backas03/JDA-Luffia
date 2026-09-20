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
    private static final String LLM_SYSTEM_PROMPT = String.join("\n",
            "You translate song lyrics into natural Korean.",
            "Rules:",
            "- Translate each input line into exactly one Korean line, in the same order.",
            "- Keep repetitions as repetitions (e.g. 憎い 憎い 憎い -> 미워 미워 미워).",
            "- Keep interjections and onomatopoeia (ああ -> 아아, Oh -> 오).",
            "- Keep proper nouns and names. Keep tone: casual speech stays casual, no polite -습니다 unless the source is polite.",
            "- If a line is already Korean, empty, or has no words, copy it unchanged.",
            "- Write the output in Korean Hangul only. Never leave Japanese kana, kanji, or Chinese characters in the output; translate them.",
            "- Never add explanations, notes, or romanization.",
            "Output JSON only: {\"t\": [\"line1\", \"line2\", ...]} with exactly the same number of items as the input.");

    private enum Mode { UNKNOWN, NLLB, LLM }

    private final String baseUrl;
    private final HttpClient httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    private volatile Mode mode = Mode.UNKNOWN;
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
        if (lines.isEmpty()) return List.of();
        Mode current = detectMode();
        List<String> result = current == Mode.LLM ? translateWithLlm(lines) : translateWithNllb(sourceLanguage, lines);
        if (result.size() != lines.size()) {
            LOGGER.warn("translator returned {} lines for {} inputs", result.size(), lines.size());
            throw new IOException("translator line count mismatch");
        }
        return result;
    }

    private Mode detectMode() throws IOException {
        Mode current = mode;
        if (current != Mode.UNKNOWN) return current;
        HttpResponse<String> response = send(HttpRequest.newBuilder(URI.create(baseUrl + "/v1/models"))
                .timeout(Duration.ofSeconds(5)).GET().build());
        current = response.statusCode() == 200 && response.body().contains("\"data\"") ? Mode.LLM : Mode.NLLB;
        mode = current;
        LOGGER.info("translator mode detected: {} ({})", current, baseUrl);
        return current;
    }

    private List<String> translateWithNllb(String sourceLanguage, List<String> lines) throws IOException {
        ObjectNode body = MAPPER.createObjectNode();
        body.put("src", sourceLanguage);
        body.put("tgt", LyricsLanguage.KOREAN);
        ArrayNode array = body.putArray("lines");
        lines.forEach(array::add);
        JsonNode node = postJson("/translate", body, Duration.ofSeconds(60));
        List<String> result = new ArrayList<>();
        for (JsonNode line : node.path("lines")) result.add(line.asText(""));
        return result;
    }

    private List<String> translateWithLlm(List<String> lines) throws IOException {
        StringBuilder user = new StringBuilder();
        user.append("Translate these ").append(lines.size()).append(" lyric lines to Korean.\n");
        for (int i = 0; i < lines.size(); i++) {
            user.append(i + 1).append(". ").append(lines.get(i) == null ? "" : lines.get(i)).append('\n');
        }
        ObjectNode body = MAPPER.createObjectNode();
        body.put("model", "translator");
        body.put("temperature", 0.2);
        body.put("max_tokens", 80 * lines.size() + 64);
        ArrayNode messages = body.putArray("messages");
        messages.addObject().put("role", "system").put("content", LLM_SYSTEM_PROMPT);
        messages.addObject().put("role", "user").put("content", user.toString());
        ObjectNode format = body.putObject("response_format");
        format.put("type", "json_object");
        ObjectNode schema = format.putObject("schema");
        schema.put("type", "object");
        ObjectNode properties = schema.putObject("properties");
        ObjectNode items = properties.putObject("t");
        items.put("type", "array");
        items.put("minItems", lines.size());
        items.put("maxItems", lines.size());
        items.putObject("items").put("type", "string");
        schema.putArray("required").add("t");
        JsonNode node = postJson("/v1/chat/completions", body, Duration.ofSeconds(240));
        String content = node.path("choices").path(0).path("message").path("content").asText("");
        JsonNode parsed;
        try {
            parsed = MAPPER.readTree(content);
        } catch (IOException e) {
            LOGGER.warn("llm translator returned non-json content: {}", content.length() > 200 ? content.substring(0, 200) : content);
            throw e;
        }
        List<String> result = new ArrayList<>();
        for (JsonNode line : parsed.path("t")) result.add(line.asText(""));
        List<Integer> leaked = new ArrayList<>();
        for (int i = 0; i < result.size() && i < lines.size(); i++) {
            String source = lines.get(i);
            if (source == null || source.isBlank() || !LyricsLanguage.needsTranslation(source)) {
                result.set(i, source == null ? "" : source);
            } else if (containsCjkScript(result.get(i))) {
                leaked.add(i);
            }
        }
        if (!leaked.isEmpty() && lines.size() > 1) {
            for (int index : leaked) {
                List<String> retried = translateWithLlm(List.of(lines.get(index)));
                result.set(index, containsCjkScript(retried.get(0)) ? lines.get(index) : retried.get(0));
            }
        } else if (!leaked.isEmpty()) {
            result.set(0, lines.get(0));
        }
        return result;
    }

    private static boolean containsCjkScript(String text) {
        if (text == null) return false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if ((c >= 0x3040 && c <= 0x30FF) || (c >= 0x4E00 && c <= 0x9FFF)) return true;
        }
        return false;
    }

    private JsonNode postJson(String path, ObjectNode body, Duration timeout) throws IOException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .header("Content-Type", "application/json")
                .timeout(timeout)
                .POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(body)))
                .build();
        HttpResponse<String> response = send(request);
        if (response.statusCode() / 100 != 2) {
            throw new IOException("translator " + response.statusCode() + ": " + response.body());
        }
        return MAPPER.readTree(response.body());
    }

    private HttpResponse<String> send(HttpRequest request) throws IOException {
        try {
            return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("translation interrupted", e);
        }
    }

    public boolean isHealthy() {
        if (baseUrl == null) return false;
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/health"))
                    .timeout(Duration.ofSeconds(3))
                    .GET()
                    .build();
            return send(request).statusCode() == 200;
        } catch (IOException e) {
            return false;
        }
    }
}
