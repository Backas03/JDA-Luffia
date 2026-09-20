package kr.kro.backas.music.source;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import kr.kro.backas.secret.BotSecret;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

public class SpotifyLoginTool {

    public static final String REDIRECT_URI = "http://127.0.0.1:8888/callback";
    public static final String SCOPES = "playlist-read-private playlist-read-collaborative";
    private static final String AUTHORIZE_URL = "https://accounts.spotify.com/authorize";
    private static final String TOKEN_URL = "https://accounts.spotify.com/api/token";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    public static void main(String[] args) throws Exception {
        String clientId = BotSecret.SPOTIFY_CLIENT_ID;
        String clientSecret = BotSecret.SPOTIFY_CLIENT_SECRET;
        if (clientId.isBlank() || clientSecret.isBlank()) {
            System.err.println("BotSecret.SPOTIFY_CLIENT_ID / SPOTIFY_CLIENT_SECRET 을 먼저 채워주세요.");
            System.exit(1);
        }

        String state = randomState();
        CompletableFuture<String> codeFuture = new CompletableFuture<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 8888), 0);
        server.createContext("/callback", exchange -> {
            Map<String, String> query = parseQuery(exchange.getRequestURI().getRawQuery());
            String body;
            if (!state.equals(query.get("state"))) {
                body = "state 가 일치하지 않습니다. 도구를 다시 실행해주세요.";
                codeFuture.completeExceptionally(new IllegalStateException("state mismatch"));
            } else if (query.containsKey("error")) {
                body = "로그인이 거부되었습니다: " + query.get("error");
                codeFuture.completeExceptionally(new IllegalStateException(query.get("error")));
            } else {
                body = "로그인 완료. 이 창을 닫고 터미널을 확인하세요.";
                codeFuture.complete(query.get("code"));
            }
            byte[] bytes = ("<html><body><h3>" + body + "</h3></body></html>").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
        server.start();

        String authorizeUrl = AUTHORIZE_URL
                + "?client_id=" + encode(clientId)
                + "&response_type=code"
                + "&redirect_uri=" + encode(REDIRECT_URI)
                + "&scope=" + encode(SCOPES)
                + "&state=" + encode(state)
                + "&show_dialog=true";

        System.out.println();
        System.out.println("=== 아래 주소를 브라우저에서 열고 Spotify 계정으로 로그인하세요 ===");
        System.out.println(authorizeUrl);
        System.out.println("=== 로그인이 끝날 때까지 이 프로그램을 종료하지 마세요 (최대 10분) ===");
        System.out.println();

        try {
            String code = codeFuture.get(10, TimeUnit.MINUTES);
            JsonNode token = exchangeCode(code, clientId, clientSecret);
            String refreshToken = token.path("refresh_token").asText(null);
            String accessToken = token.path("access_token").asText(null);
            if (refreshToken == null || accessToken == null) {
                System.err.println("토큰 발급 실패: " + token);
                System.exit(1);
            }
            JsonNode me = getJson("https://api.spotify.com/v1/me", accessToken);
            System.out.println("로그인 계정: " + me.path("display_name").asText("?") + " (" + me.path("id").asText("?") + ")");
            System.out.println("허용된 scope: " + token.path("scope").asText(""));
            System.out.println();
            System.out.println("=== 아래 값을 BotSecret.SPOTIFY_REFRESH_TOKEN 에 넣으세요 ===");
            System.out.println(refreshToken);
            System.out.println();
        } finally {
            server.stop(0);
        }
        System.exit(0);
    }

    private static JsonNode exchangeCode(String code, String clientId, String clientSecret) throws IOException, InterruptedException {
        String form = "grant_type=authorization_code"
                + "&code=" + encode(code)
                + "&redirect_uri=" + encode(REDIRECT_URI);
        return postToken(form, clientId, clientSecret);
    }

    static JsonNode postToken(String form, String clientId, String clientSecret) throws IOException, InterruptedException {
        String basic = Base64.getEncoder().encodeToString((clientId + ":" + clientSecret).getBytes(StandardCharsets.UTF_8));
        HttpRequest request = HttpRequest.newBuilder(URI.create(TOKEN_URL))
                .header("Authorization", "Basic " + basic)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form))
                .build();
        HttpResponse<String> response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() / 100 != 2) {
            throw new IOException("token endpoint " + response.statusCode() + ": " + response.body());
        }
        return MAPPER.readTree(response.body());
    }

    private static JsonNode getJson(String url, String accessToken) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .header("Authorization", "Bearer " + accessToken)
                .GET()
                .build();
        HttpResponse<String> response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
        return MAPPER.readTree(response.body());
    }

    private static Map<String, String> parseQuery(String rawQuery) {
        Map<String, String> result = new HashMap<>();
        if (rawQuery == null) return result;
        for (String pair : rawQuery.split("&")) {
            int idx = pair.indexOf('=');
            String key = idx < 0 ? pair : pair.substring(0, idx);
            String value = idx < 0 ? "" : pair.substring(idx + 1);
            result.put(URLDecoder.decode(key, StandardCharsets.UTF_8), URLDecoder.decode(value, StandardCharsets.UTF_8));
        }
        return result;
    }

    private static String randomState() {
        byte[] bytes = new byte[16];
        new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
