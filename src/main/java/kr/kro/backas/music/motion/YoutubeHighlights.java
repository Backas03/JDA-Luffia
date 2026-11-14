package kr.kro.backas.music.motion;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

final class YoutubeHighlights {

    record Marker(long startMs, long durationMs, double intensity) {
    }

    private record Candidate(long startMs, double score) {
    }

    static final long INTRO_MS = 10_000;
    static final double INTRO_RATIO = 0.05;
    static final long MIN_GAP_MS = 10_000;
    private static final String NEXT_URL = "https://www.youtube.com/youtubei/v1/next?prettyPrint=false";
    private static final String CLIENT_VERSION = "2.20251101.00.00";
    private static final String USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36";
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    private YoutubeHighlights() {
    }

    static List<Long> find(String videoId, long sceneMs, int scenes) throws IOException, InterruptedException {
        String body = MAPPER.createObjectNode()
                .put("videoId", videoId)
                .set("context", MAPPER.createObjectNode().set("client", MAPPER.createObjectNode()
                        .put("clientName", "WEB")
                        .put("clientVersion", CLIENT_VERSION)
                        .put("hl", "en")
                        .put("gl", "KR")))
                .toString();
        HttpRequest request = HttpRequest.newBuilder(URI.create(NEXT_URL))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .header("User-Agent", USER_AGENT)
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        HttpResponse<byte[]> response = HTTP.send(request, HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() / 100 != 2) throw new IOException("youtube answered " + response.statusCode() + " for the watch page of " + videoId);
        return peaks(markers(MAPPER.readTree(response.body())), sceneMs, scenes);
    }

    static List<Marker> markers(JsonNode response) {
        List<Marker> markers = new ArrayList<>();
        for (JsonNode mutation : response.path("frameworkUpdates").path("entityBatchUpdate").path("mutations")) {
            JsonNode list = mutation.path("payload").path("macroMarkersListEntity").path("markersList");
            if (!"MARKER_TYPE_HEATMAP".equals(list.path("markerType").asText())) continue;
            for (JsonNode marker : list.path("markers")) {
                long duration = marker.path("durationMillis").asLong(0);
                if (duration <= 0) continue;
                markers.add(new Marker(marker.path("startMillis").asLong(0), duration, marker.path("intensityScoreNormalized").asDouble(0)));
            }
            if (!markers.isEmpty()) break;
        }
        markers.sort(Comparator.comparingLong(Marker::startMs));
        return markers;
    }

    static List<Long> peaks(List<Marker> markers, long sceneMs, int scenes) {
        List<Long> chosen = new ArrayList<>();
        if (markers.isEmpty() || scenes <= 0) return chosen;
        Marker last = markers.get(markers.size() - 1);
        long total = last.startMs() + last.durationMs();
        long skip = Math.max(INTRO_MS, (long) (total * INTRO_RATIO));
        long gap = Math.max(sceneMs * 2, MIN_GAP_MS);
        int window = (int) Math.max(1, Math.ceil((double) sceneMs / markers.get(0).durationMs()));
        List<Candidate> candidates = new ArrayList<>();
        for (int from = 0; from + window <= markers.size(); from++) {
            Marker first = markers.get(from);
            if (first.startMs() < skip) continue;
            double score = 0;
            for (int i = from; i < from + window; i++) score += markers.get(i).intensity();
            if (score > 0) candidates.add(new Candidate(Math.max(0, Math.min(first.startMs(), total - sceneMs)), score));
        }
        candidates.sort(Comparator.comparingDouble(Candidate::score).reversed());
        for (Candidate candidate : candidates) {
            if (chosen.size() >= scenes) break;
            boolean apart = true;
            for (long taken : chosen) apart &= Math.abs(taken - candidate.startMs()) >= gap;
            if (apart) chosen.add(candidate.startMs());
        }
        Collections.sort(chosen);
        return chosen;
    }
}
