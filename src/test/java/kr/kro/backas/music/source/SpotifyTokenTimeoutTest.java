package kr.kro.backas.music.source;

import com.github.topi314.lavasrc.spotify.SpotifySourceManager;
import com.github.topi314.lavasrc.spotify.SpotifyTokenTracker;
import com.sedmelluq.discord.lavaplayer.player.DefaultAudioPlayerManager;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SpotifyTokenTimeoutTest {

    private static final long TOKENER_DELAY_MS = 3_500;

    private HttpServer tokener;
    private DefaultAudioPlayerManager manager;

    @BeforeEach
    void startSlowTokener() throws IOException {
        tokener = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        tokener.createContext("/api/token", exchange -> {
            try {
                Thread.sleep(TOKENER_DELAY_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            byte[] body = ("{\"accessToken\":\"slow-token\",\"accessTokenExpirationTimestampMs\":"
                    + (System.currentTimeMillis() + 3_600_000) + "}").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        tokener.start();
        manager = new DefaultAudioPlayerManager();
    }

    @AfterEach
    void stopTokener() {
        tokener.stop(0);
        manager.shutdown();
    }

    @Test
    void lavaplayerDefaultsGiveUpOnATokenerSlowerThanThreeSeconds() {
        SpotifySourceManager source = source();
        assertThrows(Exception.class, () -> tracker(source).getAnonymousAccessToken());
    }

    @Test
    void spotifyRequestsWaitForASlowTokener() throws Exception {
        SpotifySourceManager source = source();
        source.configureRequests(MusicSourceRegistry::spotifyRequestConfig);
        assertEquals("slow-token", tracker(source).getAnonymousAccessToken());
    }

    private SpotifySourceManager source() {
        String endpoint = "http://127.0.0.1:" + tokener.getAddress().getPort() + "/api/token";
        return new PatchedSpotifySourceManager("client", "secret", "KR", unused -> manager, null, null, null, endpoint);
    }

    private static SpotifyTokenTracker tracker(SpotifySourceManager source) throws ReflectiveOperationException {
        Field field = SpotifySourceManager.class.getDeclaredField("tokenTracker");
        field.setAccessible(true);
        return (SpotifyTokenTracker) field.get(source);
    }
}
