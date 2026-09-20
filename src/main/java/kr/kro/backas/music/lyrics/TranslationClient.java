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
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import java.util.stream.Stream;

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
    private volatile String modelName;
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
        return translate(sourceLanguage, lines, null);
    }

    public List<String> translate(String sourceLanguage, List<String> lines, @Nullable BiConsumer<Integer, String> onLine) throws IOException {
        if (baseUrl == null) throw new IOException("translator disabled");
        if (lines.isEmpty()) return List.of();
        Mode current = detectMode();
        List<String> result = current == Mode.LLM ? translateWithLlm(lines, onLine) : translateWithNllb(sourceLanguage, lines);
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
        modelName = current == Mode.LLM ? readLlmModelName(response.body()) : readNllbModelName();
        mode = current;
        LOGGER.info("translator mode detected: {} model={} ({})", current, modelName, baseUrl);
        return current;
    }

    public boolean isLlm() {
        try {
            return detectMode() == Mode.LLM;
        } catch (IOException e) {
            return false;
        }
    }

    public String getModelName() {
        String name = modelName;
        if (name == null) {
            try {
                detectMode();
            } catch (IOException e) {
                return "";
            }
            name = modelName;
        }
        return name == null ? "" : name;
    }

    private static String readLlmModelName(String body) {
        try {
            String id = MAPPER.readTree(body).path("data").path(0).path("id").asText("");
            return cleanModelName(id);
        } catch (IOException e) {
            return "";
        }
    }

    private String readNllbModelName() {
        try {
            HttpResponse<String> response = send(HttpRequest.newBuilder(URI.create(baseUrl + "/health"))
                    .timeout(Duration.ofSeconds(5)).GET().build());
            return cleanModelName(MAPPER.readTree(response.body()).path("model").asText(""));
        } catch (IOException e) {
            return "";
        }
    }

    private static String cleanModelName(String raw) {
        if (raw == null || raw.isBlank()) return "";
        String name = raw.replace('\\', '/');
        int slash = name.lastIndexOf('/');
        if (slash >= 0) name = name.substring(slash + 1);
        if (name.endsWith(".gguf")) name = name.substring(0, name.length() - 5);
        return name;
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

    private ObjectNode buildLlmRequest(List<String> lines, boolean stream) {
        StringBuilder user = new StringBuilder();
        user.append("Translate these ").append(lines.size()).append(" lyric lines to Korean.\n");
        for (int i = 0; i < lines.size(); i++) {
            user.append(i + 1).append(". ").append(lines.get(i) == null ? "" : lines.get(i)).append('\n');
        }
        ObjectNode body = MAPPER.createObjectNode();
        body.put("model", "translator");
        body.put("temperature", 0.2);
        body.put("max_tokens", 48 * lines.size() + 64);
        body.put("stream", stream);
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
        return body;
    }

    private List<String> translateWithLlm(List<String> lines, @Nullable BiConsumer<Integer, String> onLine) throws IOException {
        String content = onLine == null ? requestLlm(lines) : streamLlm(lines, onLine);
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
                List<String> retried = translateWithLlm(List.of(lines.get(index)), null);
                String fixed = containsCjkScript(retried.get(0)) ? lines.get(index) : retried.get(0);
                result.set(index, fixed);
                if (onLine != null) onLine.accept(index, fixed);
            }
        } else if (!leaked.isEmpty()) {
            result.set(0, lines.get(0));
        }
        return result;
    }

    private String requestLlm(List<String> lines) throws IOException {
        JsonNode node = postJson("/v1/chat/completions", buildLlmRequest(lines, false), Duration.ofSeconds(240));
        return node.path("choices").path(0).path("message").path("content").asText("");
    }

    private String streamLlm(List<String> lines, BiConsumer<Integer, String> onLine) throws IOException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/v1/chat/completions"))
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(240))
                .POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(buildLlmRequest(lines, true))))
                .build();
        HttpResponse<Stream<String>> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofLines());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("translation interrupted", e);
        }
        if (response.statusCode() / 100 != 2) {
            throw new IOException("translator " + response.statusCode());
        }
        StringBuilder content = new StringBuilder();
        JsonArrayStringScanner scanner = new JsonArrayStringScanner();
        try (Stream<String> body = response.body()) {
            Iterator<String> iterator = body.iterator();
            while (iterator.hasNext()) {
                String line = iterator.next();
                if (!line.startsWith("data:")) continue;
                String data = line.substring(5).trim();
                if (data.equals("[DONE]")) break;
                String delta = MAPPER.readTree(data).path("choices").path(0).path("delta").path("content").asText("");
                if (delta.isEmpty()) continue;
                content.append(delta);
                for (String element : scanner.feed(delta)) {
                    int index = scanner.completedCount() - 1;
                    if (index < lines.size() && LyricsLanguage.needsTranslation(lines.get(index)) && !containsCjkScript(element)) {
                        onLine.accept(index, element);
                    }
                }
            }
        }
        return content.toString();
    }

    private static final class JsonArrayStringScanner {
        private boolean inArray;
        private boolean inString;
        private boolean escaped;
        private int completed;
        private final StringBuilder raw = new StringBuilder();

        List<String> feed(String chunk) {
            List<String> done = new ArrayList<>();
            for (int i = 0; i < chunk.length(); i++) {
                char c = chunk.charAt(i);
                if (!inArray) {
                    if (c == '[') inArray = true;
                    continue;
                }
                if (inString) {
                    raw.append(c);
                    if (escaped) {
                        escaped = false;
                    } else if (c == '\\') {
                        escaped = true;
                    } else if (c == '"') {
                        inString = false;
                        completed++;
                        done.add(decode(raw.toString()));
                        raw.setLength(0);
                    }
                    continue;
                }
                if (c == '"') {
                    inString = true;
                    raw.append(c);
                } else if (c == ']') {
                    inArray = false;
                }
            }
            return done;
        }

        int completedCount() {
            return completed;
        }

        private static String decode(String literal) {
            try {
                return MAPPER.readTree(literal).asText("");
            } catch (IOException e) {
                return literal.substring(1, Math.max(1, literal.length() - 1));
            }
        }
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
