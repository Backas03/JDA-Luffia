package kr.kro.backas.music.source;

import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

public class SpotifyUserTokenTracker {

    private static final long EXPIRY_MARGIN_MS = 60_000;

    private final String clientId;
    private final String clientSecret;
    private final String refreshToken;
    private String accessToken;
    private long expiresAtMs;

    public SpotifyUserTokenTracker(String clientId, String clientSecret, String refreshToken) {
        this.clientId = clientId;
        this.clientSecret = clientSecret;
        this.refreshToken = refreshToken;
    }

    public synchronized String getAccessToken() throws IOException {
        if (accessToken == null || System.currentTimeMillis() >= expiresAtMs - EXPIRY_MARGIN_MS) {
            refresh();
        }
        return accessToken;
    }

    public synchronized void invalidate() {
        accessToken = null;
        expiresAtMs = 0;
    }

    private void refresh() throws IOException {
        String form = "grant_type=refresh_token&refresh_token="
                + URLEncoder.encode(refreshToken, StandardCharsets.UTF_8);
        JsonNode token;
        try {
            token = SpotifyLoginTool.postToken(form, clientId, clientSecret);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("token refresh interrupted", e);
        }
        String access = token.path("access_token").asText(null);
        if (access == null) {
            throw new IOException("refresh token 으로 access token 을 받지 못했습니다: " + token);
        }
        accessToken = access;
        expiresAtMs = System.currentTimeMillis() + token.path("expires_in").asLong(3600) * 1000;
    }
}
