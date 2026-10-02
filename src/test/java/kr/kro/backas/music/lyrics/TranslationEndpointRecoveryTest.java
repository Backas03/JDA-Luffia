package kr.kro.backas.music.lyrics;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import kr.kro.backas.music.cache.DiskCache;
import kr.kro.backas.music.llm.LlmScheduler;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TranslationEndpointRecoveryTest {

    @TempDir
    Path directory;

    @Test
    void failedEndpointComesBackWithoutWaitingForARequest() throws Exception {
        int port = freePort();
        TranslationClient client = new TranslationClient("http://127.0.0.1:" + port + "|gpu|test-model", new DiskCache(directory));
        awaitUnavailable(client);

        client.recheckFailedEndpoints();
        assertFalse(status(client).available());

        AtomicInteger calibrations = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        server.createContext("/v1/models", exchange -> respond(exchange,
                "{\"object\":\"list\",\"data\":[{\"id\":\"test-model\"}]}"));
        server.createContext("/v1/chat/completions", exchange -> {
            calibrations.incrementAndGet();
            respond(exchange, "{\"choices\":[{\"message\":{\"content\":\"1 2 3\"}}],\"usage\":{\"completion_tokens\":30}}");
        });
        server.start();
        try {
            client.recheckFailedEndpoints();
            assertTrue(status(client).available());
            assertEquals("test-model", status(client).modelName());
            assertEquals(1, calibrations.get());

            client.recheckFailedEndpoints();
            assertEquals(1, calibrations.get());
        } finally {
            server.stop(0);
        }
    }

    private static LlmScheduler.EndpointStatus status(TranslationClient client) {
        return client.scheduler().snapshot().get(0);
    }

    private static void awaitUnavailable(TranslationClient client) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 15_000;
        while (status(client).available() && System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
        }
        assertFalse(status(client).available());
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
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
