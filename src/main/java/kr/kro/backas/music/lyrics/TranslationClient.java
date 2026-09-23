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
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.nio.channels.UnresolvedAddressException;
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

    private static final long RETRY_FAILED_MS = 30_000;

    private static final class Endpoint {
        private final String baseUrl;
        private volatile Mode mode = Mode.UNKNOWN;
        private volatile String modelName = "";
        private volatile String llmModelId = "";
        private volatile long failedAt;

        private Endpoint(String baseUrl) {
            this.baseUrl = baseUrl;
        }
    }

    private final List<Endpoint> endpoints;
    private volatile Endpoint active;
    private final HttpClient httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    private final Map<String, Map<Integer, String>> cache = Collections.synchronizedMap(
            new LinkedHashMap<>(64, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Map<Integer, String>> eldest) {
                    return size() > CACHE_SIZE;
                }
            });

    public TranslationClient(@Nullable String urls) {
        List<Endpoint> parsed = new ArrayList<>();
        if (urls != null) {
            for (String url : urls.split("[,\\s]+")) {
                String trimmed = url.trim().replaceAll("/+$", "");
                if (!trimmed.isBlank()) parsed.add(new Endpoint(trimmed));
            }
        }
        this.endpoints = List.copyOf(parsed);
        if (!endpoints.isEmpty()) {
            TranslationJobs.EXECUTOR.execute(() -> {
                try {
                    pick();
                } catch (IOException e) {
                    LOGGER.warn("no translator endpoint reachable at startup: {}", e.getMessage());
                }
            });
        }
    }

    public boolean isEnabled() {
        return !endpoints.isEmpty();
    }

    @Nullable
    public String getActiveUrl() {
        Endpoint endpoint = active;
        return endpoint == null ? null : endpoint.baseUrl;
    }

    private Endpoint pick() throws IOException {
        long now = System.currentTimeMillis();
        IOException last = null;
        for (Endpoint endpoint : endpoints) {
            if (endpoint.failedAt != 0 && now - endpoint.failedAt < RETRY_FAILED_MS) continue;
            try {
                detect(endpoint);
                if (active != endpoint) {
                    active = endpoint;
                    LOGGER.info("translator endpoint in use: {} ({}, model={})", endpoint.baseUrl, endpoint.mode, endpoint.modelName);
                }
                return endpoint;
            } catch (IOException e) {
                markFailed(endpoint, e);
                last = e;
            }
        }
        throw new IOException("no translator endpoint available", last);
    }

    private void markFailed(Endpoint endpoint, IOException e) {
        endpoint.failedAt = System.currentTimeMillis();
        endpoint.mode = Mode.UNKNOWN;
        if (active == endpoint) active = null;
        LOGGER.warn("translator endpoint {} unavailable: {}", endpoint.baseUrl, e.toString());
    }

    private void detect(Endpoint endpoint) throws IOException {
        if (endpoint.mode != Mode.UNKNOWN) return;
        HttpResponse<String> response = send(HttpRequest.newBuilder(URI.create(endpoint.baseUrl + "/v1/models"))
                .timeout(Duration.ofSeconds(5)).GET().build());
        Mode detected = response.statusCode() == 200 && response.body().contains("\"data\"") ? Mode.LLM : Mode.NLLB;
        if (detected == Mode.LLM) {
            String override = System.getenv("TRANSLATOR_MODEL");
            endpoint.llmModelId = override == null || override.isBlank() ? readLlmModelId(response.body()) : override;
            endpoint.modelName = cleanModelName(endpoint.llmModelId);
        } else {
            endpoint.modelName = readNllbModelName(endpoint);
        }
        endpoint.mode = detected;
        endpoint.failedAt = 0;
        LOGGER.info("translator mode detected: {} model={} ({})", detected, endpoint.modelName, endpoint.baseUrl);
    }

    private static boolean isConnectivityFailure(Throwable error) {
        Throwable current = error;
        while (current != null) {
            if (current instanceof ConnectException || current instanceof HttpConnectTimeoutException
                    || current instanceof NoRouteToHostException || current instanceof UnresolvedAddressException) {
                return true;
            }
            String message = current.getMessage();
            if (message != null && (message.contains("Connection refused") || message.contains("translator 502")
                    || message.contains("translator 503") || message.contains("translator request failed"))) {
                return true;
            }
            current = current.getCause();
        }
        return false;
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
        if (endpoints.isEmpty()) throw new IOException("translator disabled");
        if (lines.isEmpty()) return List.of();
        IOException last = null;
        for (int attempt = 0; attempt < endpoints.size(); attempt++) {
            Endpoint endpoint = pick();
            try {
                List<String> result = endpoint.mode == Mode.LLM
                        ? translateWithLlm(endpoint, lines, onLine, cancellation)
                        : translateWithNllb(endpoint, sourceLanguage, lines);
                if (result.size() != lines.size()) {
                    LOGGER.warn("translator returned {} lines for {} inputs", result.size(), lines.size());
                    throw new IOException("translator line count mismatch");
                }
                return result;
            } catch (IOException e) {
                if (cancellation != null && cancellation.isRequested()) throw e;
                if (!isConnectivityFailure(e)) throw e;
                markFailed(endpoint, e);
                last = e;
            }
        }
        throw last == null ? new IOException("no translator endpoint available") : last;
    }

    public boolean isLlm() {
        Endpoint endpoint = active;
        return endpoint != null && endpoint.mode == Mode.LLM;
    }

    public String getModelName() {
        Endpoint endpoint = active;
        return endpoint == null ? "" : endpoint.modelName;
    }

    private static String readLlmModelId(String body) {
        try {
            return MAPPER.readTree(body).path("data").path(0).path("id").asText("");
        } catch (IOException e) {
            return "";
        }
    }

    private String readNllbModelName(Endpoint endpoint) {
        try {
            HttpResponse<String> response = send(HttpRequest.newBuilder(URI.create(endpoint.baseUrl + "/health"))
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

    private List<String> translateWithNllb(Endpoint endpoint, String sourceLanguage, List<String> lines) throws IOException {
        ObjectNode body = MAPPER.createObjectNode();
        body.put("src", sourceLanguage);
        body.put("tgt", LyricsLanguage.KOREAN);
        ArrayNode array = body.putArray("lines");
        lines.forEach(array::add);
        JsonNode node = postJson(endpoint, "/translate", body, Duration.ofSeconds(60));
        List<String> result = new ArrayList<>();
        for (JsonNode line : node.path("lines")) result.add(line.asText(""));
        return result;
    }

    private ObjectNode buildLlmRequest(Endpoint endpoint, List<String> lines, boolean stream) {
        StringBuilder user = new StringBuilder();
        user.append("Translate these ").append(lines.size()).append(" lyric lines to Korean.\n");
        for (int i = 0; i < lines.size(); i++) {
            user.append(i + 1).append(". ").append(lines.get(i) == null ? "" : lines.get(i)).append('\n');
        }
        ObjectNode body = MAPPER.createObjectNode();
        String model = endpoint.llmModelId;
        body.put("model", model == null || model.isBlank() ? "translator" : model);
        body.put("temperature", 0.2);
        body.put("reasoning_effort", "none");
        body.put("max_tokens", 56 * lines.size() + 64);
        body.put("stream", stream);
        ArrayNode messages = body.putArray("messages");
        messages.addObject().put("role", "system").put("content", LLM_SYSTEM_PROMPT);
        messages.addObject().put("role", "user").put("content", user.toString());
        ObjectNode format = body.putObject("response_format");
        format.put("type", "json_schema");
        ObjectNode jsonSchema = format.putObject("json_schema");
        jsonSchema.put("name", "lyrics_translation");
        ObjectNode schema = jsonSchema.putObject("schema");
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

    private static final int MAX_BATCH_RETRY_DEPTH = 2;
    private static final int MAX_SINGLE_LINE_RETRIES = 5;

    private List<String> translateWithLlm(Endpoint endpoint, List<String> lines, @Nullable BiConsumer<Integer, String> onLine,
                                          @Nullable Cancellation cancellation) throws IOException {
        return translateWithLlm(endpoint, lines, onLine, cancellation, 0);
    }

    private List<String> translateWithLlm(Endpoint endpoint, List<String> lines, @Nullable BiConsumer<Integer, String> onLine,
                                          @Nullable Cancellation cancellation, int depth) throws IOException {
        Map<Integer, String> byNumber = onLine == null
                ? collectInSequence(parseEntries(requestLlm(endpoint, lines)), lines.size(), null, lines)
                : streamLlm(endpoint, lines, onLine, cancellation);
        List<String> result = new ArrayList<>();
        List<Integer> missing = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            String source = lines.get(i) == null ? "" : lines.get(i);
            String translated = byNumber.get(i + 1);
            if (source.isBlank() || !LyricsLanguage.needsTranslation(source)) {
                result.add(source);
            } else if (!isAcceptable(source, translated)) {
                result.add(source);
                missing.add(i);
            } else {
                result.add(translated);
            }
        }
        if (missing.isEmpty() || lines.size() == 1) return result;
        if (cancellation != null && cancellation.isRequested()) throw new IOException("translation cancelled");
        boolean progressed = missing.size() < lines.size();
        if (missing.size() > 1 && depth < MAX_BATCH_RETRY_DEPTH && (progressed || depth == 0)) {
            List<String> sources = new ArrayList<>(missing.size());
            for (int index : missing) sources.add(lines.get(index));
            LOGGER.info("retrying {} of {} lines as one batch (pass {})", missing.size(), lines.size(), depth + 2);
            List<String> retried = translateWithLlm(endpoint, sources,
                    onLine == null ? null : (offset, text) -> onLine.accept(missing.get(offset), text),
                    cancellation, depth + 1);
            for (int j = 0; j < missing.size(); j++) result.set(missing.get(j), retried.get(j));
            return result;
        }
        int retries = 0;
        for (int index : missing) {
            if (retries++ >= MAX_SINGLE_LINE_RETRIES) break;
            if (cancellation != null && cancellation.isRequested()) throw new IOException("translation cancelled");
            String source = lines.get(index);
            String fixed = source;
            try {
                List<String> retried = translateWithLlm(endpoint, List.of(source), null, null, MAX_BATCH_RETRY_DEPTH);
                if (isAcceptable(source, retried.get(0))) fixed = retried.get(0);
            } catch (IOException e) {
                LOGGER.debug("single line retry failed for line {}", index + 1, e);
            }
            result.set(index, fixed);
            if (onLine != null && !fixed.equals(source)) onLine.accept(index, fixed);
        }
        return result;
    }

    private static List<JsonNode> parseEntries(String content) throws IOException {
        JsonNode parsed;
        try {
            parsed = MAPPER.readTree(content);
        } catch (IOException e) {
            LOGGER.warn("llm translator returned non-json content: {}", content.length() > 200 ? content.substring(0, 200) : content);
            throw e;
        }
        List<JsonNode> entries = new ArrayList<>();
        for (JsonNode entry : parsed.path("t")) entries.add(entry);
        return entries;
    }

    private static Map<Integer, String> collectInSequence(List<JsonNode> entries, int count,
                                                          @Nullable Map<Integer, String> into, List<String> lines) {
        Map<Integer, String> byNumber = into == null ? new java.util.HashMap<>() : into;
        int expected = byNumber.size() + 1;
        for (JsonNode entry : entries) {
            int n = entry.path("n").asInt(-1);
            if (byNumber.containsKey(n)) continue;
            if (n != expected) {
                LOGGER.warn("translator numbered a line {} where {} was expected ({} lines); ignoring the rest of this reply",
                        n, expected, count);
                break;
            }
            byNumber.put(n, entry.path("k").asText(""));
            expected++;
        }
        return byNumber;
    }

    private static boolean isAcceptable(String source, @Nullable String translated) {
        if (translated == null || translated.isBlank()) return false;
        if (containsCjkScript(translated)) return false;
        return translated.trim().length() >= 2 || source.trim().length() <= 2;
    }

    private String requestLlm(Endpoint endpoint, List<String> lines) throws IOException {
        JsonNode node = postJson(endpoint, "/v1/chat/completions", buildLlmRequest(endpoint, lines, false), Duration.ofSeconds(300));
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

    private Map<Integer, String> streamLlm(Endpoint endpoint, List<String> lines, BiConsumer<Integer, String> onLine,
                                           @Nullable Cancellation cancellation) throws IOException {
        if (cancellation != null && cancellation.isRequested()) throw new IOException("translation cancelled");
        HttpRequest request = HttpRequest.newBuilder(URI.create(endpoint.baseUrl + "/v1/chat/completions"))
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(300))
                .POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(buildLlmRequest(endpoint, lines, true))))
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
        JsonArrayObjectScanner scanner = new JsonArrayObjectScanner();
        Map<Integer, String> byNumber = new java.util.HashMap<>();
        boolean broken = false;
        try (Stream<String> body = response.body()) {
            if (cancellation != null) cancellation.attach(body::close);
            Iterator<String> iterator = body.iterator();
            while (!broken && iterator.hasNext()) {
                if (cancellation != null && cancellation.isRequested()) throw new IOException("translation cancelled");
                String line = iterator.next();
                if (!line.startsWith("data:")) continue;
                String data = line.substring(5).trim();
                if (data.equals("[DONE]")) break;
                String delta = MAPPER.readTree(data).path("choices").path(0).path("delta").path("content").asText("");
                if (delta.isEmpty()) continue;
                for (String objectLiteral : scanner.feed(delta)) {
                    JsonNode entry;
                    try {
                        entry = MAPPER.readTree(objectLiteral);
                    } catch (IOException e) {
                        continue;
                    }
                    int before = byNumber.size();
                    collectInSequence(List.of(entry), lines.size(), byNumber, lines);
                    if (byNumber.size() == before) {
                        int n = entry.path("n").asInt(-1);
                        if (!byNumber.containsKey(n)) {
                            broken = true;
                            break;
                        }
                        continue;
                    }
                    int n = byNumber.size();
                    String source = lines.get(n - 1);
                    String translated = byNumber.get(n);
                    if (LyricsLanguage.needsTranslation(source) && isAcceptable(source, translated)) {
                        onLine.accept(n - 1, translated);
                    }
                }
            }
        } catch (UncheckedIOException e) {
            if (cancellation != null && cancellation.isRequested()) throw new IOException("translation cancelled", e);
            if (!broken) throw e.getCause();
        }
        if (cancellation != null && cancellation.isRequested()) throw new IOException("translation cancelled");
        return byNumber;
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

    private JsonNode postJson(Endpoint endpoint, String path, ObjectNode body, Duration timeout) throws IOException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(endpoint.baseUrl + path))
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
        for (Endpoint endpoint : endpoints) {
            try {
                String path = endpoint.mode == Mode.NLLB ? "/health" : "/v1/models";
                HttpRequest request = HttpRequest.newBuilder(URI.create(endpoint.baseUrl + path))
                        .timeout(Duration.ofSeconds(3))
                        .GET()
                        .build();
                if (send(request).statusCode() == 200) return true;
            } catch (IOException ignored) {
            }
        }
        return false;
    }
}
