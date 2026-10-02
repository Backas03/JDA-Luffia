package kr.kro.backas.music.lyrics;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import kr.kro.backas.music.ai.PromptSafe;
import kr.kro.backas.music.cache.DiskCache;
import kr.kro.backas.music.llm.LlmEndpoint;
import kr.kro.backas.music.llm.LlmEndpoints;
import kr.kro.backas.music.llm.LlmLease;
import kr.kro.backas.music.llm.LlmPriority;
import kr.kro.backas.music.llm.LlmScheduler;
import kr.kro.backas.util.DiscordSafe;
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
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.function.BiConsumer;
import java.util.function.Supplier;
import java.util.stream.Stream;

public class TranslationClient {

    private static final Logger LOGGER = LoggerFactory.getLogger(TranslationClient.class);
    private static final int CACHE_SIZE = 200;
    private static final int MAX_TRANSLATION_GROWTH = 4;
    private static final int TRANSLATION_GROWTH_SLACK = 30;
    private static final int ALIGN_WINDOW = 3;
    private static final int TOKENS_PER_LINE = 25;
    private static final java.util.regex.Pattern MARKUP_TAG = java.util.regex.Pattern.compile("</?[A-Za-z][A-Za-z0-9-]*(\\s[^<>]*)?/?>");
    private static final java.util.regex.Pattern ALIGNMENT_NOISE = java.util.regex.Pattern.compile(
            "[\\s\\p{Punct}「」『』【】〈〉《》、。・…〜～♪☆★‘’“”]");
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String LLM_SYSTEM_PROMPT = String.join("\n",
            "You translate song lyrics into natural Korean.",
            "Before translating, read every line to understand the song's story, speaker and mood (the song title and artist, when given, are context only).",
            "Rules:",
            "- Translate so the Korean reads like natural song lyrics that keep the same feeling (longing, excitement, anger, tenderness...), not a word-for-word gloss.",
            "- Keep one consistent speaker voice and way of addressing the listener (for example 나/너) across all lines.",
            "- Keep lines short and rhythmic like singable lyrics. Do not add meaning that is not in the original.",
            "- Translate each input line into exactly one Korean line, in the same order.",
            "- Keep repetitions as repetitions (e.g. 憎い 憎い 憎い -> 미워 미워 미워).",
            "- Keep interjections and onomatopoeia (ああ -> 아아, Oh -> 오).",
            "- Keep proper nouns and names. Keep tone: casual speech stays casual, no polite -습니다 unless the source is polite.",
            "- If a line is already Korean, empty, or has no words, copy it unchanged.",
            "- Write the output in Korean Hangul only. Never leave Japanese kana, kanji, or Chinese characters in the output; translate them.",
            "- Never add explanations, notes, or romanization.",
            "Output JSON only: {\"t\": [{\"n\": 1, \"s\": \"夜明け\", \"k\": \"translation of line 1\"}, {\"n\": 2, \"s\": \"君の声\", \"k\": \"translation of line 2\"}, ...]}",
            "with exactly one object per input line, n = the input line number, s = the first three characters of that input line copied exactly, k = the Korean translation of that line only.",
            "Never merge or split lines, even when a sentence continues on the next line: every numbered line gets its own translation.",
            PromptSafe.DATA_RULE);
    private static final String CALIBRATION_PROMPT = "Write the numbers from 1 to 60 separated by single spaces and nothing else.";

    private interface LeaseCall<T> {
        T run(LlmLease lease) throws IOException;
    }

    private final List<LlmEndpoint> endpoints;
    private final LlmScheduler scheduler;
    private final DiskCache diskCache;
    private final HttpClient httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    private final Map<String, Map<Integer, String>> cache = Collections.synchronizedMap(
            new LinkedHashMap<>(64, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Map<Integer, String>> eldest) {
                    return size() > CACHE_SIZE;
                }
            });

    public TranslationClient(@Nullable String urls) {
        this(urls, DiskCache.defaultCache());
    }

    public TranslationClient(@Nullable String urls, DiskCache diskCache) {
        this.diskCache = diskCache;
        this.endpoints = LlmEndpoints.parse(urls);
        this.scheduler = new LlmScheduler(endpoints);
        for (LlmEndpoint endpoint : endpoints) {
            TranslationJobs.EXECUTOR.execute(() -> {
                try {
                    detect(endpoint);
                    if (endpoint.mode() == LlmEndpoint.Mode.LLM && !endpoint.isFallback()) calibrate(endpoint);
                } catch (IOException e) {
                    markFailed(endpoint, e);
                }
            });
        }
    }

    public boolean isEnabled() {
        return !endpoints.isEmpty();
    }

    public LlmScheduler scheduler() {
        return scheduler;
    }

    public String computeSummary() {
        return scheduler.summary();
    }

    public boolean hasUsableGpu() {
        return scheduler.hasUsableGpu();
    }

    public int parallelism() {
        return scheduler.parallelism();
    }

    public void wakeScheduler() {
        scheduler.wake();
    }

    public boolean isLlm() {
        for (LlmEndpoint endpoint : endpoints) {
            if (endpoint.mode() == LlmEndpoint.Mode.LLM) return true;
        }
        return false;
    }

    public String getModelName() {
        LlmEndpoint last = scheduler.lastUsed();
        if (last != null && !last.modelName().isBlank()) return last.modelName();
        for (LlmEndpoint endpoint : endpoints) {
            if (!endpoint.modelName().isBlank()) return endpoint.modelName();
        }
        return "";
    }

    private void markFailed(LlmEndpoint endpoint, IOException e) {
        endpoint.markFailed(System.currentTimeMillis());
        LOGGER.warn("translator endpoint {} unavailable: {}", endpoint.baseUrl(), e.toString());
    }

    private void detect(LlmEndpoint endpoint) throws IOException {
        if (endpoint.mode() != LlmEndpoint.Mode.UNKNOWN) return;
        HttpResponse<String> response = send(HttpRequest.newBuilder(URI.create(endpoint.baseUrl() + "/v1/models"))
                .timeout(Duration.ofSeconds(5)).GET().build());
        if (response.statusCode() == 200 && response.body().contains("\"data\"")) {
            String override = System.getenv("TRANSLATOR_MODEL");
            String modelId;
            if (!endpoint.preferredModel().isBlank()) {
                modelId = endpoint.preferredModel();
            } else if (override != null && !override.isBlank() && response.body().contains("\"" + override + "\"")) {
                modelId = override;
            } else {
                modelId = readLlmModelId(response.body());
            }
            endpoint.markDetected(LlmEndpoint.Mode.LLM, modelId, cleanModelName(modelId));
        } else {
            endpoint.markDetected(LlmEndpoint.Mode.NLLB, "", readNllbModelName(endpoint));
        }
        LOGGER.info("translator mode detected: {} model={} ({}, {} slot(s){})", endpoint.mode(), endpoint.modelName(),
                endpoint.baseUrl(), endpoint.slots(), endpoint.isFallback() ? ", fallback" : "");
    }

    private void calibrate(LlmEndpoint endpoint) {
        Set<LlmEndpoint> others = new HashSet<>(endpoints);
        others.remove(endpoint);
        try {
            measureCalibration(endpoint, others);
            int slots = endpoint.slots();
            if (slots > 1) {
                List<CompletableFuture<Void>> runs = new ArrayList<>();
                for (int i = 0; i < slots; i++) {
                    runs.add(CompletableFuture.runAsync(() -> {
                        try {
                            measureCalibration(endpoint, others);
                        } catch (IOException e) {
                            throw new UncheckedIOException(e);
                        }
                    }, TranslationJobs.EXECUTOR));
                }
                CompletableFuture.allOf(runs.toArray(CompletableFuture[]::new)).join();
                LOGGER.info("{} calibrated at about {} token/s alone and {} token/s each with {} at once", endpoint.label(),
                        Math.round(endpoint.speed().estimate(1)), Math.round(endpoint.speed().estimate(slots)), slots);
            } else {
                LOGGER.info("{} calibrated at about {} token/s", endpoint.label(), Math.round(endpoint.speed().estimate(1)));
            }
        } catch (IOException | RuntimeException e) {
            LOGGER.warn("failed to calibrate {}: {}", endpoint.label(), e.toString());
        }
    }

    private void measureCalibration(LlmEndpoint endpoint, Set<LlmEndpoint> others) throws IOException {
        try (LlmLease lease = scheduler.acquire(() -> LlmPriority.BACKGROUND, 120, others)) {
            ObjectNode body = MAPPER.createObjectNode();
            body.put("model", modelOf(lease));
            body.put("temperature", 0.0);
            body.put("reasoning_effort", "none");
            body.put("max_tokens", 160);
            body.putArray("messages").addObject().put("role", "user").put("content", CALIBRATION_PROMPT);
            long startedAt = System.currentTimeMillis();
            JsonNode node = postJson(endpoint, "/v1/chat/completions", body, Duration.ofSeconds(60));
            lease.complete(node.path("usage").path("completion_tokens").asLong(0), System.currentTimeMillis() - startedAt);
        }
    }

    private <T> T withLease(Supplier<LlmPriority> priority, int estimatedTokens, boolean llmOnly, LeaseCall<T> call)
            throws IOException {
        if (endpoints.isEmpty()) throw new IOException("translator disabled");
        Set<LlmEndpoint> excluded = new HashSet<>();
        IOException last = null;
        for (int attempt = 0; attempt < endpoints.size(); attempt++) {
            LlmLease lease = scheduler.acquire(priority, estimatedTokens, excluded);
            LlmEndpoint endpoint = lease.endpoint();
            try {
                try {
                    detect(endpoint);
                } catch (IOException e) {
                    markFailed(endpoint, e);
                    excluded.add(endpoint);
                    last = e;
                    continue;
                }
                if (llmOnly && endpoint.mode() != LlmEndpoint.Mode.LLM) {
                    excluded.add(endpoint);
                    last = new IOException("llm endpoint unavailable: " + endpoint.baseUrl());
                    continue;
                }
                return call.run(lease);
            } catch (IOException e) {
                if (lease.wasPreempted()) throw e;
                if (!isConnectivityFailure(e)) throw e;
                markFailed(endpoint, e);
                excluded.add(endpoint);
                last = e;
            } finally {
                lease.close();
            }
        }
        throw last == null ? new IOException("no translator endpoint available") : last;
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
        return cache.computeIfAbsent(trackKey, this::loadTranslations);
    }

    private Map<Integer, String> loadTranslations(String trackKey) {
        Map<Integer, String> lines = new ConcurrentHashMap<>();
        JsonNode stored = diskCache.read(translationPath(trackKey));
        if (stored != null) {
            stored.path("lines").fields().forEachRemaining(entry -> {
                try {
                    String text = entry.getValue().asText("");
                    if (!text.isBlank()) lines.put(Integer.parseInt(entry.getKey()), text);
                } catch (NumberFormatException ignored) {
                }
            });
            if (!lines.isEmpty()) LOGGER.info("loaded {} cached translated line(s) for {}", lines.size(), trackKey);
        }
        return lines;
    }

    public void persist(String trackKey) {
        Map<Integer, String> lines = cache.get(trackKey);
        if (lines == null || lines.isEmpty()) return;
        ObjectNode stored = MAPPER.createObjectNode();
        stored.put("model", getModelName());
        ObjectNode translated = stored.putObject("lines");
        lines.forEach((index, text) -> {
            if (text != null && !text.isBlank()) translated.put(String.valueOf(index), text);
        });
        if (!translated.isEmpty()) diskCache.write(translationPath(trackKey), stored);
    }

    private static String translationPath(String trackKey) {
        return "translations/" + DiskCache.safeName(trackKey) + ".json";
    }

    private volatile Process fallbackProcess;

    public void startFallbackManager(java.util.concurrent.ScheduledExecutorService executor) {
        String startCommand = System.getenv("TRANSLATOR_FALLBACK_START");
        if (fallbackEndpoint() == null || startCommand == null || startCommand.isBlank()) return;
        LOGGER.info("translator fallback manager enabled: {}", startCommand);
        executor.scheduleWithFixedDelay(
                () -> TranslationJobs.EXECUTOR.execute(() -> manageFallback(startCommand)),
                30, 30, java.util.concurrent.TimeUnit.SECONDS);
    }

    @Nullable
    private LlmEndpoint fallbackEndpoint() {
        for (LlmEndpoint endpoint : endpoints) {
            if (endpoint.isFallback()) return endpoint;
        }
        return null;
    }

    private void manageFallback(String startCommand) {
        try {
            boolean gpuUp = false;
            for (LlmEndpoint endpoint : endpoints) {
                if (!endpoint.isFallback() && endpointResponds(endpoint)) {
                    gpuUp = true;
                    break;
                }
            }
            boolean fallbackUp = isFallbackRunning();
            if (gpuUp && fallbackUp) {
                stopFallback();
            } else if (!gpuUp && !fallbackUp) {
                startFallback(startCommand);
            }
        } catch (RuntimeException e) {
            LOGGER.warn("translator fallback manager tick failed", e);
        }
    }

    private boolean endpointResponds(LlmEndpoint endpoint) {
        try {
            String path = endpoint.mode() == LlmEndpoint.Mode.NLLB ? "/health" : "/v1/models";
            HttpRequest request = HttpRequest.newBuilder(URI.create(endpoint.baseUrl() + path))
                    .timeout(Duration.ofSeconds(3))
                    .GET()
                    .build();
            return send(request).statusCode() == 200;
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    private boolean isFallbackRunning() {
        Process process = fallbackProcess;
        if (process != null && process.isAlive()) return true;
        LlmEndpoint fallback = fallbackEndpoint();
        return fallback != null && endpointResponds(fallback);
    }

    private void startFallback(String startCommand) {
        try {
            LOGGER.info("every gpu translator is down, starting cpu fallback server: {}", startCommand);
            fallbackProcess = new ProcessBuilder("/bin/sh", "-c", startCommand)
                    .redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .start();
        } catch (IOException e) {
            LOGGER.warn("failed to start cpu fallback server", e);
        }
    }

    private void stopFallback() {
        Process process = fallbackProcess;
        if (process != null && process.isAlive()) {
            LOGGER.info("a gpu translator is healthy, stopping cpu fallback server");
            process.descendants().forEach(ProcessHandle::destroy);
            process.destroy();
            fallbackProcess = null;
            return;
        }
        String stopCommand = System.getenv("TRANSLATOR_FALLBACK_STOP");
        if (stopCommand == null || stopCommand.isBlank()) return;
        LOGGER.info("a gpu translator is healthy, stopping external cpu fallback: {}", stopCommand);
        try {
            new ProcessBuilder("/bin/sh", "-c", stopCommand).start();
        } catch (IOException e) {
            LOGGER.warn("failed to stop external cpu fallback", e);
        }
    }

    public List<String> translate(String sourceLanguage, List<String> lines) throws IOException {
        return translate(sourceLanguage, lines, null);
    }

    public List<String> translate(String sourceLanguage, List<String> lines, @Nullable BiConsumer<Integer, String> onLine) throws IOException {
        return translate(sourceLanguage, lines, onLine, null);
    }

    public List<String> translate(String sourceLanguage, List<String> lines, @Nullable BiConsumer<Integer, String> onLine,
                                  @Nullable Cancellation cancellation) throws IOException {
        return translate(sourceLanguage, lines, onLine, cancellation, null);
    }

    public List<String> translate(String sourceLanguage, List<String> lines, @Nullable BiConsumer<Integer, String> onLine,
                                  @Nullable Cancellation cancellation, @Nullable String songContext) throws IOException {
        return translate(sourceLanguage, lines, onLine, cancellation, songContext, () -> LlmPriority.INTERACTIVE);
    }

    public List<String> translate(String sourceLanguage, List<String> lines, @Nullable BiConsumer<Integer, String> onLine,
                                  @Nullable Cancellation cancellation, @Nullable String songContext,
                                  Supplier<LlmPriority> priority) throws IOException {
        if (lines.isEmpty()) return List.of();
        return withLease(priority, TOKENS_PER_LINE * lines.size() + 64, false, lease -> {
            if (cancellation != null) lease.onPreempt(cancellation::preempt);
            List<String> result = lease.endpoint().mode() == LlmEndpoint.Mode.LLM
                    ? translateWithLlm(lease, lines, onLine, cancellation, songContext, 0)
                    : translateWithNllb(lease.endpoint(), sourceLanguage, lines);
            if (result.size() != lines.size()) {
                LOGGER.warn("translator returned {} lines for {} inputs", result.size(), lines.size());
                throw new IOException("translator line count mismatch");
            }
            return result;
        });
    }

    private static String readLlmModelId(String body) {
        try {
            return MAPPER.readTree(body).path("data").path(0).path("id").asText("");
        } catch (IOException e) {
            return "";
        }
    }

    private String readNllbModelName(LlmEndpoint endpoint) {
        try {
            HttpResponse<String> response = send(HttpRequest.newBuilder(URI.create(endpoint.baseUrl() + "/health"))
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

    private static String modelOf(LlmLease lease) {
        String model = lease.endpoint().modelId();
        return model == null || model.isBlank() ? "translator" : model;
    }

    private List<String> translateWithNllb(LlmEndpoint endpoint, String sourceLanguage, List<String> lines) throws IOException {
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

    private ObjectNode buildLlmRequest(LlmLease lease, List<String> lines, boolean stream, @Nullable String songContext,
                                       boolean retry) {
        StringBuilder user = new StringBuilder();
        user.append("Translate these ").append(lines.size()).append(" lyric lines to Korean.\n<lyrics>\n");
        if (songContext != null && !songContext.isBlank()) {
            user.append("song: ").append(PromptSafe.data(songContext)).append("\nlines:\n");
        }
        for (int i = 0; i < lines.size(); i++) {
            user.append(i + 1).append(". ").append(PromptSafe.lyricLine(lines.get(i))).append('\n');
        }
        user.append("</lyrics>\n");
        if (retry) {
            user.append("These lines were not translated into Korean before. Do not copy the original text: translate the meaning of every line into Korean Hangul.\nLanguages:");
            for (int i = 0; i < lines.size(); i++) {
                user.append(" line ").append(i + 1).append(": ").append(LyricsLanguage.describe(lines.get(i)))
                        .append(i + 1 < lines.size() ? "," : ".\n");
            }
        }
        ObjectNode schema = MAPPER.createObjectNode();
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
        ObjectNode prefix = itemProperties.putObject("s");
        prefix.put("type", "string");
        prefix.put("minLength", 1);
        prefix.put("maxLength", 12);
        itemProperties.putObject("k").put("type", "string");
        item.putArray("required").add("n").add("s").add("k");
        schema.putArray("required").add("t");
        ObjectNode body = chatRequest(lease, LLM_SYSTEM_PROMPT, user.toString(), retry ? RETRY_TEMPERATURE : 0.2, 68 * lines.size() + 64,
                "lyrics_translation", schema);
        body.put("stream", stream);
        if (stream) body.putObject("stream_options").put("include_usage", true);
        return body;
    }

    private ObjectNode chatRequest(LlmLease lease, String systemPrompt, String userPrompt, double temperature,
                                   int maxTokens, String schemaName, ObjectNode schema) {
        ObjectNode body = MAPPER.createObjectNode();
        body.put("model", modelOf(lease));
        body.put("temperature", temperature);
        body.put("reasoning_effort", "none");
        body.put("max_tokens", maxTokens);
        ArrayNode messages = body.putArray("messages");
        messages.addObject().put("role", "system").put("content", systemPrompt);
        messages.addObject().put("role", "user").put("content", userPrompt);
        ObjectNode format = body.putObject("response_format");
        format.put("type", "json_schema");
        ObjectNode jsonSchema = format.putObject("json_schema");
        jsonSchema.put("name", schemaName);
        jsonSchema.set("schema", schema);
        return body;
    }

    public JsonNode chat(ArrayNode messages, ArrayNode tools, int maxTokens) throws IOException {
        return withLease(() -> LlmPriority.INTERACTIVE, maxTokens, true, lease -> {
            ObjectNode body = MAPPER.createObjectNode();
            body.put("model", modelOf(lease));
            body.put("temperature", 0.0);
            body.put("reasoning_effort", "none");
            body.put("max_tokens", maxTokens);
            body.set("messages", messages);
            body.set("tools", tools);
            body.put("tool_choice", "auto");
            long startedAt = System.currentTimeMillis();
            JsonNode node = postJson(lease.endpoint(), "/v1/chat/completions", body, Duration.ofSeconds(120));
            lease.complete(node.path("usage").path("completion_tokens").asLong(0), System.currentTimeMillis() - startedAt);
            return node.path("choices").path(0).path("message");
        });
    }

    public JsonNode requestJson(String systemPrompt, String userPrompt, String schemaName, ObjectNode schema,
                                int maxTokens) throws IOException {
        return requestJson(systemPrompt, userPrompt, schemaName, schema, maxTokens, LlmPriority.INTERACTIVE);
    }

    public JsonNode requestJson(String systemPrompt, String userPrompt, String schemaName, ObjectNode schema,
                                int maxTokens, LlmPriority priority) throws IOException {
        return withLease(() -> priority, maxTokens, true, lease -> {
            long startedAt = System.currentTimeMillis();
            JsonNode node = postJson(lease.endpoint(), "/v1/chat/completions",
                    chatRequest(lease, systemPrompt, userPrompt, 0.0, maxTokens, schemaName, schema),
                    Duration.ofSeconds(120));
            lease.complete(node.path("usage").path("completion_tokens").asLong(0), System.currentTimeMillis() - startedAt);
            return MAPPER.readTree(node.path("choices").path(0).path("message").path("content").asText(""));
        });
    }

    private static final int MAX_BATCH_RETRY_DEPTH = 2;
    private static final int MAX_SINGLE_LINE_RETRIES = 20;
    private static final double RETRY_TEMPERATURE = 0.5;

    private List<String> translateWithLlm(LlmLease lease, List<String> lines, @Nullable BiConsumer<Integer, String> onLine,
                                          @Nullable Cancellation cancellation, @Nullable String songContext, int depth) throws IOException {
        Map<Integer, String> byNumber;
        if (onLine == null) {
            byNumber = new java.util.HashMap<>();
            collectAligned(parseEntries(requestLlm(lease, lines, songContext, depth > 0)), byNumber, lines);
        } else {
            byNumber = streamLlm(lease, lines, onLine, songContext, cancellation, depth > 0);
        }
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
            List<String> retried = translateWithLlm(lease, sources,
                    onLine == null ? null : (offset, text) -> onLine.accept(missing.get(offset), text),
                    cancellation, songContext, depth + 1);
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
                List<String> retried = translateWithLlm(lease, List.of(source), null, null, songContext, MAX_BATCH_RETRY_DEPTH);
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

    static List<Integer> collectAligned(List<JsonNode> entries, Map<Integer, String> byNumber, List<String> lines) {
        List<Integer> filled = new ArrayList<>();
        for (JsonNode entry : entries) {
            int n = entry.path("n").asInt(-1);
            String prefix = entry.path("s").asText("");
            int line = alignedLine(lines, n, prefix, byNumber);
            if (line < 1) {
                LOGGER.warn("translator line {} (starts with '{}') matches no nearby source line; ignoring it", n, prefix);
                continue;
            }
            if (line != n) LOGGER.info("translator line {} realigned to line {} by its source prefix", n, line);
            byNumber.put(line, cleanTranslation(entry.path("k").asText("")));
            filled.add(line);
        }
        return filled;
    }

    static int alignedLine(List<String> lines, int n, String prefix, Map<Integer, String> filled) {
        String key = alignmentKey(prefix);
        if (key.isEmpty()) return n >= 1 && n <= lines.size() && !filled.containsKey(n) ? n : -1;
        for (int distance = 0; distance <= ALIGN_WINDOW; distance++) {
            int[] candidates = distance == 0 ? new int[]{n} : new int[]{n + distance, n - distance};
            for (int candidate : candidates) {
                if (candidate < 1 || candidate > lines.size() || filled.containsKey(candidate)) continue;
                String source = alignmentKey(lines.get(candidate - 1));
                if (!source.isEmpty() && source.startsWith(key)) return candidate;
            }
        }
        return -1;
    }

    static String cleanTranslation(@Nullable String text) {
        if (text == null) return "";
        return MARKUP_TAG.matcher(text).replaceAll("").replaceAll("\\s+", " ").strip();
    }

    static String alignmentKey(@Nullable String text) {
        if (text == null) return "";
        String normalized = java.text.Normalizer.normalize(text, java.text.Normalizer.Form.NFKC);
        return ALIGNMENT_NOISE.matcher(normalized).replaceAll("").toLowerCase(java.util.Locale.ROOT);
    }

    static boolean isAcceptable(String source, @Nullable String translated) {
        if (translated == null || translated.isBlank()) return false;
        if (containsForeignScript(translated)) return false;
        if (DiscordSafe.hasLinkOrMention(translated) && !DiscordSafe.hasLinkOrMention(source)) return false;
        if (translated.length() > source.length() * MAX_TRANSLATION_GROWTH + TRANSLATION_GROWTH_SLACK) return false;
        if (translated.strip().equalsIgnoreCase(source.strip()) && LyricsLanguage.needsTranslation(source)) return false;
        return translated.trim().length() >= 2 || source.trim().length() <= 2;
    }

    private String requestLlm(LlmLease lease, List<String> lines, @Nullable String songContext, boolean retry) throws IOException {
        long startedAt = System.currentTimeMillis();
        JsonNode node = postJson(lease.endpoint(), "/v1/chat/completions", buildLlmRequest(lease, lines, false, songContext, retry),
                Duration.ofSeconds(300));
        lease.complete(node.path("usage").path("completion_tokens").asLong(0), System.currentTimeMillis() - startedAt);
        return node.path("choices").path(0).path("message").path("content").asText("");
    }

    public static final class Cancellation {
        private volatile boolean requested;
        private volatile boolean preempted;
        private volatile Runnable abort;

        public void cancel() {
            requested = true;
            Runnable current = abort;
            if (current != null) current.run();
        }

        public void preempt() {
            preempted = true;
            cancel();
        }

        public boolean isRequested() {
            return requested;
        }

        public boolean wasPreempted() {
            return preempted;
        }

        private void attach(Runnable abort) {
            this.abort = abort;
            if (requested) abort.run();
        }
    }

    private Map<Integer, String> streamLlm(LlmLease lease, List<String> lines, BiConsumer<Integer, String> onLine,
                                           @Nullable String songContext,
                                           @Nullable Cancellation cancellation, boolean retry) throws IOException {
        if (cancellation != null && cancellation.isRequested()) throw new IOException("translation cancelled");
        HttpRequest request = HttpRequest.newBuilder(URI.create(lease.endpoint().baseUrl() + "/v1/chat/completions"))
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(300))
                .POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(buildLlmRequest(lease, lines, true, songContext, retry))))
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
        long firstTokenAt = 0;
        long deltaCount = 0;
        long usageTokens = 0;
        try (Stream<String> body = response.body()) {
            if (cancellation != null) cancellation.attach(body::close);
            Iterator<String> iterator = body.iterator();
            while (iterator.hasNext()) {
                if (cancellation != null && cancellation.isRequested()) throw new IOException("translation cancelled");
                String line = iterator.next();
                if (!line.startsWith("data:")) continue;
                String data = line.substring(5).trim();
                if (data.equals("[DONE]")) break;
                JsonNode chunk = MAPPER.readTree(data);
                long completion = chunk.path("usage").path("completion_tokens").asLong(0);
                if (completion > 0) usageTokens = completion;
                String delta = chunk.path("choices").path(0).path("delta").path("content").asText("");
                if (delta.isEmpty()) continue;
                if (firstTokenAt == 0) firstTokenAt = System.currentTimeMillis();
                deltaCount++;
                long streamedMs = System.currentTimeMillis() - firstTokenAt;
                if (streamedMs > 1000) lease.reportLiveSpeed(deltaCount * 1000.0 / streamedMs);
                for (String objectLiteral : scanner.feed(delta)) {
                    JsonNode entry;
                    try {
                        entry = MAPPER.readTree(objectLiteral);
                    } catch (IOException e) {
                        continue;
                    }
                    for (int n : collectAligned(List.of(entry), byNumber, lines)) {
                        String source = lines.get(n - 1);
                        String translated = byNumber.get(n);
                        if (LyricsLanguage.needsTranslation(source) && isAcceptable(source, translated)) {
                            onLine.accept(n - 1, translated);
                        }
                    }
                }
            }
        } catch (UncheckedIOException e) {
            if (cancellation != null && cancellation.isRequested()) throw new IOException("translation cancelled", e);
            throw e.getCause();
        }
        if (cancellation != null && cancellation.isRequested()) throw new IOException("translation cancelled");
        if (firstTokenAt > 0) {
            lease.complete(usageTokens > 0 ? usageTokens : deltaCount, System.currentTimeMillis() - firstTokenAt);
        }
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

    private static boolean containsForeignScript(String text) {
        if (text == null) return false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if ((c >= 0x3040 && c <= 0x30FF) || (c >= 0x4E00 && c <= 0x9FFF) || (c >= 0x0400 && c <= 0x04FF)) return true;
        }
        return false;
    }

    private JsonNode postJson(LlmEndpoint endpoint, String path, ObjectNode body, Duration timeout) throws IOException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(endpoint.baseUrl() + path))
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
        for (LlmEndpoint endpoint : endpoints) {
            if (endpointResponds(endpoint)) return true;
        }
        return false;
    }
}
