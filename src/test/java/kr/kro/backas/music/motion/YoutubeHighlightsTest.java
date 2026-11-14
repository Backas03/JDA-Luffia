package kr.kro.backas.music.motion;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class YoutubeHighlightsTest {

    private static List<YoutubeHighlights.Marker> flat(int count, long durationMs, double intensity) {
        List<YoutubeHighlights.Marker> markers = new ArrayList<>();
        for (int i = 0; i < count; i++) markers.add(new YoutubeHighlights.Marker(i * durationMs, durationMs, intensity));
        return markers;
    }

    private static void raise(List<YoutubeHighlights.Marker> markers, int index, double intensity) {
        YoutubeHighlights.Marker marker = markers.get(index);
        markers.set(index, new YoutubeHighlights.Marker(marker.startMs(), marker.durationMs(), intensity));
    }

    @Test
    void readsTheHeatmapOutOfTheWatchPageUpdates() throws Exception {
        String json = """
                {"frameworkUpdates":{"entityBatchUpdate":{"mutations":[
                  {"payload":{"somethingElse":{}}},
                  {"payload":{"macroMarkersListEntity":{"markersList":{"markerType":"MARKER_TYPE_CHAPTERS","markers":[
                    {"startMillis":"0","durationMillis":"1000","intensityScoreNormalized":1}]}}}},
                  {"payload":{"macroMarkersListEntity":{"markersList":{"markerType":"MARKER_TYPE_HEATMAP","markers":[
                    {"startMillis":"2000","durationMillis":"2000","intensityScoreNormalized":0.25},
                    {"startMillis":"0","durationMillis":"2000","intensityScoreNormalized":1}]}}}}
                ]}}}""";
        List<YoutubeHighlights.Marker> markers = YoutubeHighlights.markers(new ObjectMapper().readTree(json));
        assertEquals(List.of(new YoutubeHighlights.Marker(0, 2000, 1), new YoutubeHighlights.Marker(2000, 2000, 0.25)), markers);
    }

    @Test
    void aVideoWithoutAHeatmapHasNoPeak() throws Exception {
        assertTrue(YoutubeHighlights.markers(new ObjectMapper().readTree("{}")).isEmpty());
        assertTrue(YoutubeHighlights.peaks(List.of(), 6000, 3).isEmpty());
    }

    @Test
    void theOpeningSpikeIsNotTheHighlight() {
        List<YoutubeHighlights.Marker> markers = flat(100, 2000, 0.2);
        raise(markers, 0, 1.0);
        raise(markers, 40, 0.6);
        raise(markers, 41, 0.7);
        raise(markers, 42, 0.6);
        assertEquals(List.of(80_000L), YoutubeHighlights.peaks(markers, 6000, 1));
    }

    @Test
    void theClipNeverRunsPastTheEnd() {
        List<YoutubeHighlights.Marker> markers = flat(100, 2000, 0.2);
        raise(markers, 99, 1.0);
        assertEquals(List.of(194_000L), YoutubeHighlights.peaks(markers, 6000, 1));
    }

    @Test
    void aVideoShorterThanTheSkippedOpeningHasNoPeak() {
        assertTrue(YoutubeHighlights.peaks(flat(4, 2000, 0.5), 6000, 3).isEmpty());
    }

    @Test
    void severalScenesComeFromDifferentPartsInPlayingOrder() {
        List<YoutubeHighlights.Marker> markers = flat(100, 2000, 0.1);
        raise(markers, 70, 0.9);
        raise(markers, 71, 0.8);
        raise(markers, 30, 0.7);
        raise(markers, 50, 0.5);
        assertEquals(List.of(60_000L, 100_000L, 140_000L), YoutubeHighlights.peaks(markers, 2000, 3));
    }

    @Test
    void aVideoWithFewPeaksStillFillsTheScenesFromElsewhere() {
        List<YoutubeHighlights.Marker> markers = flat(100, 2000, 0.1);
        raise(markers, 70, 0.9);
        List<Long> scenes = YoutubeHighlights.peaks(markers, 2000, 3);
        assertEquals(3, scenes.size());
        assertTrue(scenes.contains(140_000L));
        for (int i = 1; i < scenes.size(); i++) assertTrue(scenes.get(i) - scenes.get(i - 1) >= YoutubeHighlights.MIN_GAP_MS);
    }
}
