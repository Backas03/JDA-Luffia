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

public final class WhisperClient {

    public record Word(String text, long startMs, long endMs) {
    }

    public record Endpoint(String baseUrl, String label) {
    }

    private static final Logger LOGGER = LoggerFactory.getLogger(WhisperClient.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    static final long RETRY_FAILED_MS = Config.get().whisper().retryFailedSeconds() * 1000L;
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(3);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(90);

    private final List<Endpoint> endpoints;
    private final Map<String, Long> failedAt = new ConcurrentHashMap<>();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build();

    public WhisperClient(@Nullable String urls) {
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

    public List<Word> transcribe(byte[] wav, @Nullable String language) throws IOException {
        IOException last = null;
        for (Endpoint endpoint : endpoints) {
            if (isDown(endpoint)) continue;
            try {
                List<Word> words = request(endpoint, wav, language);
                failedAt.remove(endpoint.baseUrl());
                return words;
            } catch (IOException e) {
                failedAt.put(endpoint.baseUrl(), System.currentTimeMillis());
                LOGGER.info("whisper endpoint {} unavailable, trying the next one: {}", endpoint.label(), e.toString());
                last = e;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("whisper request interrupted", e);
            }
        }
        throw last == null ? new IOException("no whisper endpoint available") : last;
    }

    private List<Word> request(Endpoint endpoint, byte[] wav, @Nullable String language) throws IOException, InterruptedException {
        String boundary = "luffia-" + UUID.randomUUID();
        HttpRequest request = HttpRequest.newBuilder(URI.create(endpoint.baseUrl() + "/v1/audio/transcriptions"))
                .timeout(REQUEST_TIMEOUT)
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(multipart(boundary, wav, language)))
                .build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() != 200) {
            throw new IOException("whisper " + response.statusCode() + ": " + abbreviate(response.body()));
        }
        return parseWords(response.body());
    }

    static byte[] multipart(String boundary, byte[] wav, @Nullable String language) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(wav.length + 512);
        out.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"audio.wav\"\r\n"
                + "Content-Type: audio/wav\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        out.write(wav);
        out.write("\r\n".getBytes(StandardCharsets.UTF_8));
        if (language != null && !language.isBlank()) {
            out.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"language\"\r\n\r\n" + language + "\r\n")
                    .getBytes(StandardCharsets.UTF_8));
        }
        out.write(("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        return out.toByteArray();
    }

    static List<Word> parseWords(String json) throws IOException {
        JsonNode root = MAPPER.readTree(json);
        List<Word> words = new ArrayList<>();
        for (JsonNode node : root.path("words")) {
            String text = node.path("word").asText("");
            if (text.isBlank()) continue;
            long start = Math.round(node.path("start").asDouble(0) * 1000);
            long end = Math.round(node.path("end").asDouble(0) * 1000);
            words.add(new Word(text.trim(), start, Math.max(start, end)));
        }
        return words;
    }

    private static String abbreviate(String body) {
        return body.length() > 200 ? body.substring(0, 200) + "..." : body;
    }
}
