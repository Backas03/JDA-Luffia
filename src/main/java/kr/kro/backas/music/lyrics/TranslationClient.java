package kr.kro.backas.music.lyrics;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
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
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
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
            "Output JSON only: {\"t\": [{\"n\": 1, \"k\": \"translation of line 1\"}, {\"n\": 2, \"k\": \"translation of line 2\"}, ...]}",
            "with exactly one object per input line, n = the input line number, k = the Korean translation of that line only. Never merge or split lines.");

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
        return translate(sourceLanguage, lines, onLine, null);
    }

    public List<String> translate(String sourceLanguage, List<String> lines, @Nullable BiConsumer<Integer, String> onLine,
                                  @Nullable Cancellation cancellation) throws IOException {
        if (baseUrl == null) throw new IOException("translator disabled");
        if (lines.isEmpty()) return List.of();
        Mode current = detectMode();
        List<String> result = current == Mode.LLM ? translateWithLlm(lines, onLine, cancellation) : translateWithNllb(sourceLanguage, lines);
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
        body.put("max_tokens", 56 * lines.size() + 64);
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
        ObjectNode item = items.putObject("items");
        item.put("type", "object");
        ObjectNode itemProperties = item.putObject("properties");
        ObjectNode number = itemProperties.putObject("n");
        number.put("type", "integer");
        number.put("minimum", 1);
        number.put("maximum", lines.size());
        itemProperties.putObject("k").put("type", "string");
        item.putArray("required").add("n").add("k");
        schema.putArray("required").add("t");
        return body;
    }

    private List<String> translateWithLlm(List<String> lines, @Nullable BiConsumer<Integer, String> onLine,
                                          @Nullable Cancellation cancellation) throws IOException {
        String content = onLine == null ? requestLlm(lines) : streamLlm(lines, onLine, cancellation);
        JsonNode parsed;
        try {
            parsed = MAPPER.readTree(content);
        } catch (IOException e) {
            LOGGER.warn("llm translator returned non-json content: {}", content.length() > 200 ? content.substring(0, 200) : content);
            throw e;
        }
        Map<Integer, String> byNumber = new java.util.HashMap<>();
        for (JsonNode entry : parsed.path("t")) {
            int n = entry.path("n").asInt(-1);
            if (n >= 1 && n <= lines.size() && !byNumber.containsKey(n)) byNumber.put(n, entry.path("k").asText(""));
        }
        List<String> result = new ArrayList<>();
        List<Integer> retry = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            String source = lines.get(i) == null ? "" : lines.get(i);
            String translated = byNumber.get(i + 1);
            if (source.isBlank() || !LyricsLanguage.needsTranslation(source)) {
                result.add(source);
            } else if (!isAcceptable(source, translated)) {
                result.add(source);
                retry.add(i);
            } else {
                result.add(translated);
            }
        }
        if (!retry.isEmpty() && lines.size() > 1) {
            for (int index : retry) {
                if (cancellation != null && cancellation.isRequested()) throw new IOException("translation cancelled");
                String source = lines.get(index);
                String fixed = source;
                try {
                    List<String> retried = translateWithLlm(List.of(source), null, null);
                    if (isAcceptable(source, retried.get(0))) fixed = retried.get(0);
                } catch (IOException e) {
                    LOGGER.debug("single line retry failed for line {}", index + 1, e);
                }
                result.set(index, fixed);
                if (onLine != null && !fixed.equals(source)) onLine.accept(index, fixed);
            }
        }
        return result;
    }

    private static boolean isAcceptable(String source, @Nullable String translated) {
        if (translated == null || translated.isBlank()) return false;
        if (containsCjkScript(translated)) return false;
        return translated.trim().length() >= 2 || source.trim().length() <= 2;
    }

    private String requestLlm(List<String> lines) throws IOException {
        JsonNode node = postJson("/v1/chat/completions", buildLlmRequest(lines, false), Duration.ofSeconds(300));
        return node.path("choices").path(0).path("message").path("content").asText("");
    }

    public static final class Cancellation {
        private volatile boolean requested;
        private volatile Runnable abort;

        public void cancel() {
            requested = true;
            Runnable current = abort;
            if (current != null) current.run();
        }

        public boolean isRequested() {
            return requested;
        }

        private void attach(Runnable abort) {
            this.abort = abort;
            if (requested) abort.run();
        }
    }

    private String streamLlm(List<String> lines, BiConsumer<Integer, String> onLine, @Nullable Cancellation cancellation) throws IOException {
        if (cancellation != null && cancellation.isRequested()) throw new IOException("translation cancelled");
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/v1/chat/completions"))
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(300))
                .POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(buildLlmRequest(lines, true))))
                .build();
        CompletableFuture<HttpResponse<Stream<String>>> pending = httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofLines());
        if (cancellation != null) cancellation.attach(() -> pending.cancel(true));
        HttpResponse<Stream<String>> response;
        try {
            response = pending.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("translation interrupted", e);
        } catch (CancellationException e) {
            throw new IOException("translation cancelled", e);
        } catch (ExecutionException e) {
            throw new IOException("translator request failed", e.getCause() == null ? e : e.getCause());
        }
        if (response.statusCode() / 100 != 2) {
            throw new IOException("translator " + response.statusCode());
        }
        StringBuilder content = new StringBuilder();
        JsonArrayObjectScanner scanner = new JsonArrayObjectScanner();
        java.util.Set<Integer> delivered = new java.util.HashSet<>();
        try (Stream<String> body = response.body()) {
            if (cancellation != null) cancellation.attach(body::close);
            Iterator<String> iterator = body.iterator();
            while (iterator.hasNext()) {
                if (cancellation != null && cancellation.isRequested()) throw new IOException("translation cancelled");
                String line = iterator.next();
                if (!line.startsWith("data:")) continue;
                String data = line.substring(5).trim();
                if (data.equals("[DONE]")) break;
                String delta = MAPPER.readTree(data).path("choices").path(0).path("delta").path("content").asText("");
                if (delta.isEmpty()) continue;
                content.append(delta);
                for (String objectLiteral : scanner.feed(delta)) {
                    JsonNode entry;
                    try {
                        entry = MAPPER.readTree(objectLiteral);
                    } catch (IOException e) {
                        continue;
                    }
                    int n = entry.path("n").asInt(-1);
                    if (n < 1 || n > lines.size() || !delivered.add(n)) continue;
                    String source = lines.get(n - 1);
                    String translated = entry.path("k").asText("");
                    if (LyricsLanguage.needsTranslation(source) && isAcceptable(source, translated)) {
                        onLine.accept(n - 1, translated);
                    }
                }
            }
        } catch (UncheckedIOException e) {
            if (cancellation != null && cancellation.isRequested()) throw new IOException("translation cancelled", e);
            throw e.getCause();
        }
        if (cancellation != null && cancellation.isRequested()) throw new IOException("translation cancelled");
        return content.toString();
    }

    private static final class JsonArrayObjectScanner {
        private boolean inArray;
        private boolean inString;
        private boolean escaped;
        private int depth;
        private final StringBuilder current = new StringBuilder();

        List<String> feed(String chunk) {
            List<String> done = new ArrayList<>();
            for (int i = 0; i < chunk.length(); i++) {
                char c = chunk.charAt(i);
                if (!inArray) {
                    if (c == '[') inArray = true;
                    continue;
                }
                if (depth > 0) current.append(c);
                if (inString) {
                    if (escaped) escaped = false;
                    else if (c == '\\') escaped = true;
                    else if (c == '"') inString = false;
                    continue;
                }
                if (c == '"') {
                    inString = true;
                } else if (c == '{') {
                    if (depth == 0) {
                        current.setLength(0);
                        current.append(c);
                    }
                    depth++;
                } else if (c == '}') {
                    depth--;
                    if (depth == 0) {
                        done.add(current.toString());
                        current.setLength(0);
                    }
                } else if (c == ']' && depth == 0) {
                    inArray = false;
                }
            }
            return done;
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
