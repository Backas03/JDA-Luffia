package kr.kro.backas.music.lyrics.sync;

import kr.kro.backas.config.Config;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class AsrClient {

    public record Word(String text, long startMs, long endMs) {
    }

    public record Endpoint(String baseUrl, String label) {
    }

    public record Segment(long startMs, long endMs, String text) {
    }

    public record Transcript(List<Word> words, List<Segment> segments) {
    }

    private static final Logger LOGGER = LoggerFactory.getLogger(AsrClient.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    static final long RETRY_FAILED_MS = Config.get().asr().retryFailedSeconds() * 1000L;
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(3);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(90);
    static final String AUTO_LANGUAGE = "auto";
    private static final char BROKEN = '�';

    private final List<Endpoint> endpoints;
    private final Map<String, Long> failedAt = new ConcurrentHashMap<>();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build();

    public AsrClient(@Nullable String urls) {
        this.endpoints = parse(urls);
    }

    public static List<Endpoint> parse(@Nullable String urls) {
        List<Endpoint> endpoints = new ArrayList<>();
        if (urls == null) return endpoints;
        for (String entry : urls.split(",")) {
            String[] parts = entry.trim().split("\\|", 2);
            String base = parts[0].trim().replaceAll("/+$", "");
            if (base.isBlank()) continue;
            String label = parts.length > 1 && !parts[1].isBlank() ? parts[1].trim() : hostOf(base);
            endpoints.add(new Endpoint(base, label));
        }
        return List.copyOf(endpoints);
    }

    private static String hostOf(String base) {
        try {
            String host = URI.create(base).getHost();
            return host == null || host.isBlank() ? base : host;
        } catch (RuntimeException e) {
            LOGGER.debug("could not read the host of {}", base, e);
            return base;
        }
    }

    public List<Endpoint> endpoints() {
        return endpoints;
    }

    public boolean isConfigured() {
        return !endpoints.isEmpty();
    }

    public boolean isAvailable() {
        for (Endpoint endpoint : endpoints) {
            if (!isDown(endpoint)) return true;
        }
        return false;
    }

    private boolean isDown(Endpoint endpoint) {
        Long failed = failedAt.get(endpoint.baseUrl());
        return failed != null && System.currentTimeMillis() - failed < RETRY_FAILED_MS;
    }

    public List<Word> transcribe(byte[] wav, @Nullable String language, @Nullable String prompt) throws IOException {
        return transcribeDetailed(wav, language, prompt).words();
    }

    public Transcript transcribeDetailed(byte[] wav, @Nullable String language, @Nullable String prompt) throws IOException {
        IOException last = null;
        for (Endpoint endpoint : endpoints) {
            if (isDown(endpoint)) continue;
            try {
                Transcript transcript = request(endpoint, wav, language, prompt);
                failedAt.remove(endpoint.baseUrl());
                return transcript;
            } catch (IOException e) {
                failedAt.put(endpoint.baseUrl(), System.currentTimeMillis());
                LOGGER.info("asr endpoint {} unavailable, trying the next one: {}", endpoint.label(), e.toString());
                last = e;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("asr request interrupted", e);
            }
        }
        throw last == null ? new IOException("no asr endpoint available") : last;
    }

    private Transcript request(Endpoint endpoint, byte[] wav, @Nullable String language, @Nullable String prompt) throws IOException, InterruptedException {
        String boundary = "luffia-" + UUID.randomUUID();
        HttpRequest request = HttpRequest.newBuilder(URI.create(transcriptionUrl(endpoint.baseUrl())))
                .timeout(REQUEST_TIMEOUT)
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(multipart(boundary, wav, language, prompt)))
                .build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() != 200) {
            throw new IOException("asr " + response.statusCode() + ": " + abbreviate(response.body()));
        }
        return new Transcript(parseWords(response.body()), parseSegments(response.body()));
    }

    static String transcriptionUrl(String base) {
        String path = URI.create(base).getPath();
        return path == null || path.isEmpty() || path.equals("/") ? base + "/v1/audio/transcriptions" : base;
    }

    static byte[] multipart(String boundary, byte[] wav, @Nullable String language, @Nullable String prompt) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(wav.length + 512);
        out.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"audio.wav\"\r\n"
                + "Content-Type: audio/wav\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        out.write(wav);
        out.write("\r\n".getBytes(StandardCharsets.UTF_8));
        String sentLanguage = language == null || language.isBlank() ? AUTO_LANGUAGE : language.strip();
        out.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"language\"\r\n\r\n" + sentLanguage + "\r\n")
                .getBytes(StandardCharsets.UTF_8));
        if (prompt != null && !prompt.isBlank()) {
            out.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"prompt\"\r\n\r\n" + prompt.strip() + "\r\n")
                    .getBytes(StandardCharsets.UTF_8));
        }
        out.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"response_format\"\r\n\r\nverbose_json\r\n")
                .getBytes(StandardCharsets.UTF_8));
        out.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"max_context\"\r\n\r\n0\r\n")
                .getBytes(StandardCharsets.UTF_8));
        out.write(("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        return out.toByteArray();
    }

    static List<Word> parseWords(String json) throws IOException {
        JsonNode root = MAPPER.readTree(json);
        List<Word> words = new ArrayList<>();
        addWords(root.path("words"), words);
        if (words.isEmpty()) {
            for (JsonNode segment : root.path("segments")) addSegmentWords(segment, words);
        }
        return words;
    }

    private static void addSegmentWords(JsonNode segment, List<Word> words) {
        List<Word> tokens = new ArrayList<>();
        for (JsonNode node : segment.path("words")) {
            long start = Math.round(node.path("start").asDouble(0) * 1000);
            long end = Math.round(node.path("end").asDouble(0) * 1000);
            tokens.add(new Word(node.path("word").asText(""), start, Math.max(start, end)));
        }
        List<Word> repaired = repairSplitCharacters(segment.path("text").asText(""), tokens);
        for (Word token : repaired == null ? tokens : repaired) {
            String text = token.text().replace(String.valueOf(BROKEN), "");
            if (text.isBlank()) continue;
            words.add(new Word(text.trim(), token.startMs(), token.endMs()));
        }
    }

    @Nullable
    static List<Word> repairSplitCharacters(String text, List<Word> tokens) {
        if (text.indexOf(BROKEN) >= 0) return null;
        boolean broken = false;
        for (Word token : tokens) broken |= token.text().indexOf(BROKEN) >= 0;
        if (!broken) return null;
        List<StringBuilder> rebuilt = new ArrayList<>(tokens.size());
        int cursor = 0;
        int owner = -1;
        for (int i = 0; i < tokens.size(); i++) {
            StringBuilder current = new StringBuilder();
            rebuilt.add(current);
            String token = tokens.get(i).text();
            for (int k = 0; k < token.length(); k++) {
                char c = token.charAt(k);
                if (c == BROKEN) {
                    if (owner < 0) owner = i;
                    continue;
                }
                int at = owner < 0 ? cursor : text.indexOf(c, cursor + 1);
                if (at < 0 || at >= text.length() || text.charAt(at) != c) return null;
                if (owner >= 0) {
                    if (!restoreInto(rebuilt.get(owner), text, cursor, at)) return null;
                    owner = -1;
                }
                current.append(c);
                cursor = at + 1;
            }
        }
        if (owner >= 0 && !restoreInto(rebuilt.get(owner), text, cursor, text.length())) return null;
        if (owner < 0 && !text.substring(cursor).isBlank()) return null;
        List<Word> words = new ArrayList<>(tokens.size());
        for (int i = 0; i < tokens.size(); i++) {
            Word token = tokens.get(i);
            String restored = rebuilt.get(i).toString();
            if (restored.isEmpty() && !words.isEmpty()) {
                Word last = words.removeLast();
                words.add(new Word(last.text(), last.startMs(), Math.max(last.endMs(), token.endMs())));
                continue;
            }
            words.add(new Word(restored, token.startMs(), token.endMs()));
        }
        return words;
    }

    private static boolean restoreInto(StringBuilder owner, String text, int from, int to) {
        if (to <= from) return false;
        for (int k = from; k < to; k++) {
            if (text.charAt(k) < 0x80) return false;
        }
        owner.append(text, from, to);
        return true;
    }

    static List<Segment> parseSegments(String json) throws IOException {
        List<Segment> segments = new ArrayList<>();
        for (JsonNode node : MAPPER.readTree(json).path("segments")) {
            String text = node.path("text").asText("").replace("�", "").strip();
            if (text.isEmpty()) continue;
            long start = Math.round(node.path("start").asDouble(0) * 1000);
            long end = Math.round(node.path("end").asDouble(0) * 1000);
            segments.add(new Segment(start, Math.max(start, end), text));
        }
        return segments;
    }

    private static void addWords(JsonNode nodes, List<Word> words) {
        for (JsonNode node : nodes) {
            String text = node.path("word").asText("").replace("�", "");
            if (text.isBlank()) continue;
            long start = Math.round(node.path("start").asDouble(0) * 1000);
            long end = Math.round(node.path("end").asDouble(0) * 1000);
            words.add(new Word(text.trim(), start, Math.max(start, end)));
        }
    }

    private static String abbreviate(String body) {
        return body.length() > 200 ? body.substring(0, 200) + "..." : body;
    }
}
