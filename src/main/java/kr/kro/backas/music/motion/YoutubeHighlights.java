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
import java.util.Comparator;
import java.util.List;

final class YoutubeHighlights {

    record Marker(long startMs, long durationMs, double intensity) {
    }

    static final long NONE = -1;
    static final long INTRO_MS = 10_000;
    static final double INTRO_RATIO = 0.05;
    private static final String NEXT_URL = "https://www.youtube.com/youtubei/v1/next?prettyPrint=false";
    private static final String CLIENT_VERSION = "2.20251101.00.00";
    private static final String USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36";
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    private YoutubeHighlights() {
    }

    static long find(String videoId, long clipMs) throws IOException, InterruptedException {
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
        return peakStartMs(markers(MAPPER.readTree(response.body())), clipMs);
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

    static long peakStartMs(List<Marker> markers, long clipMs) {
        if (markers.isEmpty()) return NONE;
        Marker last = markers.get(markers.size() - 1);
        long total = last.startMs() + last.durationMs();
        long skip = Math.max(INTRO_MS, (long) (total * INTRO_RATIO));
        int window = (int) Math.max(1, Math.ceil((double) clipMs / markers.get(0).durationMs()));
        long best = NONE;
        double bestScore = 0;
        for (int from = 0; from + window <= markers.size(); from++) {
            Marker first = markers.get(from);
            if (first.startMs() < skip) continue;
            double score = 0;
            for (int i = from; i < from + window; i++) score += markers.get(i).intensity();
            if (score > bestScore) {
                bestScore = score;
                best = first.startMs();
            }
        }
        if (best == NONE) return NONE;
        return Math.max(0, Math.min(best, total - clipMs));
    }
}
