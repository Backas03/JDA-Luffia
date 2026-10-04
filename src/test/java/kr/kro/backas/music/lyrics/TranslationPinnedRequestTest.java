package kr.kro.backas.music.lyrics;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import kr.kro.backas.music.cache.DiskCache;
import kr.kro.backas.music.llm.LlmEndpoint;
import kr.kro.backas.music.llm.LlmPriority;
import kr.kro.backas.music.llm.LlmScheduler;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TranslationPinnedRequestTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String CALIBRATION_MARK = "Write the numbers";

    @TempDir
    Path directory;

    @Test
    void pinnedRequestsOnlyGoToTheChosenGpu() throws Exception {
        try (FakeGpu first = new FakeGpu(); FakeGpu second = new FakeGpu()) {
            TranslationClient client = client(first, second);
            List<LlmEndpoint> gpus = client.liveGpus();
            assertEquals(List.of("gpu-a", "gpu-b"), gpus.stream().map(LlmEndpoint::label).toList());

            for (int i = 0; i < 3; i++) assertTrue(request(client, gpus.get(1)).path("ok").asBoolean());
            assertEquals(0, first.requests.get());
            assertEquals(3, second.requests.get());

            request(client, gpus.get(0));
            assertEquals(1, first.requests.get());
        }
    }

    @Test
    void pinnedRequestMovesToAnotherGpuWhenItsOwnIsDown() throws Exception {
        try (FakeGpu first = new FakeGpu(); FakeGpu second = new FakeGpu()) {
            TranslationClient client = client(first, second);
            LlmEndpoint down = client.liveGpus().get(1);
            second.close();

            assertTrue(request(client, down).path("ok").asBoolean());
            assertEquals(1, first.requests.get());
            assertEquals(List.of("gpu-a"), client.liveGpus().stream().map(LlmEndpoint::label).toList());
        }
    }

    private static JsonNode request(TranslationClient client, LlmEndpoint target) throws IOException {
        ObjectNode schema = MAPPER.createObjectNode();
        schema.put("type", "object");
        return client.requestJson("system", "user", "check", schema, 64, LlmPriority.INTERACTIVE, target);
    }

    private TranslationClient client(FakeGpu first, FakeGpu second) throws InterruptedException {
        TranslationClient client = new TranslationClient(
                first.url() + "|gpu-a|test-model|1," + second.url() + "|gpu-b|test-model|1,http://127.0.0.1:1|cpu||fallback",
                new DiskCache(directory));
        long deadline = System.currentTimeMillis() + 15_000;
        while (System.currentTimeMillis() < deadline) {
            List<LlmScheduler.EndpointStatus> statuses = client.scheduler().snapshot();
            boolean ready = first.calibrations.get() >= 1 && second.calibrations.get() >= 1
                    && statuses.get(0).inUse() == 0 && statuses.get(1).inUse() == 0
                    && !statuses.get(0).modelName().isBlank() && !statuses.get(1).modelName().isBlank();
            if (ready) return client;
            Thread.sleep(50);
        }
        throw new IllegalStateException("fake gpus were not detected in time");
    }

    private static final class FakeGpu implements AutoCloseable {
        private final HttpServer server;
        private final AtomicInteger requests = new AtomicInteger();
        private final AtomicInteger calibrations = new AtomicInteger();

        private FakeGpu() throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.setExecutor(Executors.newCachedThreadPool());
            server.createContext("/v1/models", exchange -> respond(exchange,
                    "{\"object\":\"list\",\"data\":[{\"id\":\"test-model\"}]}"));
            server.createContext("/v1/chat/completions", this::complete);
            server.start();
        }

        private String url() {
            return "http://127.0.0.1:" + server.getAddress().getPort();
        }

        private void complete(HttpExchange exchange) throws IOException {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            if (body.contains(CALIBRATION_MARK)) {
                calibrations.incrementAndGet();
            } else {
                requests.incrementAndGet();
            }
            respond(exchange, "{\"choices\":[{\"message\":{\"content\":\"{\\\"ok\\\":true}\"}}],\"usage\":{\"completion_tokens\":30}}");
        }

        private static void respond(HttpExchange exchange, String body) throws IOException {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }
}
