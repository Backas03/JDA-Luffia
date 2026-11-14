package kr.kro.backas.music.lyrics;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import kr.kro.backas.music.cache.DiskCache;
import kr.kro.backas.music.llm.LlmEndpoint;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TranslationSlotDetectionTest {
    private static final int SLOTS = 4;
    private static final long REQUEST_MS = 400;

    @TempDir
    Path directory;

    @Test
    void countsRequestsThatFinishTogetherWithTheFastest() {
        assertEquals(1, TranslationClient.servedAtOnce(new long[]{1836, 5465, 3657, 7275}));
        assertEquals(4, TranslationClient.servedAtOnce(new long[]{2000, 2100, 1950, 2050}));
        assertEquals(2, TranslationClient.servedAtOnce(new long[]{2000, 2050, 4000, 4100}));
        assertEquals(3, TranslationClient.servedAtOnce(new long[]{0, 0, 0}));
    }

    @Test
    void serverAnsweringOneAtATimeGetsOneSlot() throws Exception {
        assertEquals(1, slotsAfterCalibration(Executors.newSingleThreadExecutor()));
    }

    @Test
    void serverAnsweringInParallelKeepsItsSlots() throws Exception {
        assertEquals(SLOTS, slotsAfterCalibration(Executors.newCachedThreadPool()));
    }

    private int slotsAfterCalibration(ExecutorService workers) throws Exception {
        AtomicInteger answered = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(workers);
        server.createContext("/v1/models", exchange -> respond(exchange,
                "{\"object\":\"list\",\"data\":[{\"id\":\"test-model\"}]}"));
        server.createContext("/v1/chat/completions", exchange -> {
            try {
                Thread.sleep(REQUEST_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            respond(exchange, "{\"choices\":[{\"message\":{\"content\":\"1 2 3\"}}],\"usage\":{\"completion_tokens\":30}}");
            answered.incrementAndGet();
        });
        server.start();
        try {
            TranslationClient client = new TranslationClient(
                    "http://127.0.0.1:" + server.getAddress().getPort() + "|gpu|test-model|" + SLOTS, new DiskCache(directory));
            LlmEndpoint endpoint = client.scheduler().endpoints().get(0);
            assertEquals(SLOTS, endpoint.configuredSlots());
            long deadline = System.currentTimeMillis() + 20_000;
            while (answered.get() < SLOTS + 1 && System.currentTimeMillis() < deadline) Thread.sleep(20);
            assertEquals(SLOTS + 1, answered.get());
            Thread.sleep(300);
            return endpoint.slots();
        } finally {
            server.stop(0);
            workers.shutdownNow();
        }
    }

    private static void respond(HttpExchange exchange, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
