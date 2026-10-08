package kr.kro.backas.music.lyrics;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LrcLibClientTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static String lrcLine(long ms, String text) {
        return String.format("[%02d:%02d.%02d] %s\\n", ms / 60_000, ms / 1000 % 60, ms / 10 % 100, text);
    }

    private static boolean hasPair(List<String[]> pairs, String artist, String title) {
        for (String[] pair : pairs) {
            if (pair[0].equals(artist) && pair[1].equals(title)) return true;
        }
        return false;
    }

    @Test
    void takesTheBracketedTitleEvenWhenNothingComesBeforeIt() {
        List<String[]> pairs = LrcLibClient.candidatePairs("「夜明けの歌」（アニメ「Sample Show」オープニング映像）", "Sample Channel");
        assertTrue(hasPair(pairs, "Sample Channel", "夜明けの歌"));
        List<String[]> prefixed = LrcLibClient.candidatePairs("Sample Band「夜明けの歌」Music Video", "Sample Channel");
        assertTrue(hasPair(prefixed, "Sample Band", "夜明けの歌"));
    }

    @Test
    void acceptsTheSameSongEvenWhenTheLengthDiffers() throws Exception {
        JsonNode results = MAPPER.readTree("""
                [{"id": 1, "trackName": "Other Song", "artistName": "Sample Band", "duration": 200, "syncedLyrics": "[00:01.00] a"},
                 {"id": 2, "trackName": "夜明けの歌", "artistName": "Another Artist", "duration": 190, "plainLyrics": "a"},
                 {"id": 3, "trackName": "夜明けの歌", "artistName": "Sample Band", "duration": 190, "syncedLyrics": "[00:01.00] a"},
                 {"id": 4, "trackName": "夜明けの歌", "artistName": "SAMPLE BAND!", "duration": 207, "plainLyrics": "a", "syncedLyrics": "[00:01.00] a"}]""");
        ArrayNode same = LrcLibClient.sameSong(results, new SongResolver.Song("夜明けの歌", "Sample Band"), "");
        assertEquals(2, same.size());
        JsonNode picked = LrcLibClient.withPlainFirst(same);
        assertNotNull(picked);
        assertEquals(4, picked.path("id").asInt());
    }

    @Test
    void acceptsAnArtistNamedInTheVideoWhenTheAiNamedTheChannel() throws Exception {
        JsonNode results = MAPPER.readTree("""
                [{"id": 2, "trackName": "夜明けの歌", "artistName": "Another Artist", "duration": 190, "plainLyrics": "a"},
                 {"id": 3, "trackName": "夜明けの歌", "artistName": "BandName!!", "duration": 190, "syncedLyrics": "[00:01.00] a"}]""");
        String videoKey = LrcLibClient.matchKey("「夜明けの歌」（アニメ「Series It's BandName!!」オープニング映像） Series Official");
        ArrayNode same = LrcLibClient.sameSong(results, new SongResolver.Song("夜明けの歌", "Series Official"), videoKey);
        assertEquals(1, same.size());
        assertEquals(3, same.get(0).path("id").asInt());
    }

    @Test
    void doesNotGuessWithoutEvidenceForTheArtistOrAnExactTitle() throws Exception {
        JsonNode results = MAPPER.readTree("""
                [{"id": 3, "trackName": "夜明けの歌", "artistName": "Sample Band", "duration": 190, "syncedLyrics": "[00:01.00] a"}]""");
        assertEquals(0, LrcLibClient.sameSong(results, new SongResolver.Song("夜明けの歌", ""), "").size());
        assertEquals(0, LrcLibClient.sameSong(results, new SongResolver.Song("夜明け", "Sample Band"), "").size());
        assertEquals(0, LrcLibClient.sameSong(results, new SongResolver.Song("夜明けの歌", "Different Band"), "unrelatedvideo").size());
        assertEquals(0, LrcLibClient.sameSong(null, new SongResolver.Song("夜明けの歌", "Sample Band"), "").size());
        assertNull(LrcLibClient.withPlainFirst(MAPPER.createArrayNode()));
    }

    @Test
    void dropsTimestampsWhenTheLengthDiffers() {
        Lyrics synced = new Lyrics("t", "a", null, List.of(new LyricLine(1000, "first line"), new LyricLine(2000, "second line")), false);
        Lyrics plain = LrcLibClient.plainOnly(synced);
        assertFalse(plain.hasSynced());
        assertEquals("first line\nsecond line", plain.plain());
        Lyrics both = new Lyrics("t", "a", "kept text", List.of(new LyricLine(1000, "first line")), false);
        assertEquals("kept text", LrcLibClient.plainOnly(both).plain());
    }

    @Test
    void dropsTheSourceTagThatCoverTitlesCarry() {
        assertEquals("Dan Dan Kokoro Hikareteku", LrcLibClient.cleanTitle("Dan Dan Kokoro Hikareteku (From \"Dragon Ball GT\")"));
        assertEquals("Let It Go", LrcLibClient.cleanTitle("Let It Go [From \"Frozen\"]"));
        assertEquals("Love from Paris", LrcLibClient.cleanTitle("Love from Paris"));
    }

    @Test
    void passesOverSyncedLyricsThatStopHalfwayThroughTheSong() throws Exception {
        JsonNode results = MAPPER.readTree("""
                [{"id": 1, "trackName": "Sample Song", "artistName": "Cover Singer", "duration": 215.0,
                  "syncedLyrics": "[00:03.70] first line\\n[01:36.54] last short line\\n[01:43.21] "},
                 {"id": 2, "trackName": "Sample Song", "artistName": "Other Singer", "duration": 211.0,
                  "syncedLyrics": "[00:03.46] first line\\n[03:11.59] last full line\\n[03:22.35] "}]""");
        assertTrue(LrcLibClient.unreliableSync(results.get(0), 214));
        assertFalse(LrcLibClient.unreliableSync(results.get(1), 214));
        assertEquals(2, LrcLibClient.pickBest(results, 214).path("id").asInt());

        ArrayNode onlyShort = MAPPER.createArrayNode().add(results.get(0));
        assertEquals(1, LrcLibClient.pickBest(onlyShort, 214).path("id").asInt());
    }

    @Test
    void distrustsSyncedLyricsSqueezedIntoAFewSeconds() throws Exception {
        StringBuilder crowded = new StringBuilder();
        StringBuilder spread = new StringBuilder();
        for (int i = 0; i < 30; i++) {
            crowded.append(lrcLine(50_000 + i * 500L, "line " + i));
            spread.append(lrcLine(5_000 + i * 6_000L, "line " + i));
        }
        JsonNode squeezed = MAPPER.readTree("{\"syncedLyrics\": \"" + crowded + "[03:10.00] \"}");
        JsonNode normal = MAPPER.readTree("{\"syncedLyrics\": \"" + spread + "[03:10.00] \"}");
        assertTrue(LrcLibClient.unreliableSync(squeezed, 0));
        assertFalse(LrcLibClient.unreliableSync(normal, 202));
    }

    @Test
    void comparesNamesIgnoringWidthCaseAndPunctuation() {
        assertEquals(LrcLibClient.matchKey("MyGO!!!!!"), LrcLibClient.matchKey("ｍｙｇｏ"));
        assertEquals("", LrcLibClient.matchKey("「」！？"));
    }

    @Test
    void offsetTagShiftsEveryTimestampAndNeverGoesNegative() {
        var shifted = LrcLibClient.parseLrc("[offset:+500]\n[00:00.30]첫 줄\n[00:10.00]둘째 줄");
        assertEquals(0, shifted.get(0).timeMs());
        assertEquals(9_500, shifted.get(1).timeMs());
        var delayed = LrcLibClient.parseLrc("[offset:-250]\n[00:10.00]둘째 줄");
        assertEquals(10_250, delayed.get(0).timeMs());
        assertEquals(0, LrcLibClient.lrcOffsetMs("[00:10.00]둘째 줄"));
    }
}
