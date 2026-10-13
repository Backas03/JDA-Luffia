package kr.kro.backas.music.lyrics;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.sedmelluq.discord.lavaplayer.track.AudioTrackInfo;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CompletableFuture;

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
    void learnsOtherSpellingsOfAnArtistFromHowLrclibNamesThem() throws Exception {
        JsonNode results = MAPPER.readTree("""
                [{"id": 1, "trackName": "セカイ / 스텔라이브(StelLive) cover", "artistName": "스텔라이브 StelLive Official"},
                 {"id": 2, "trackName": "Shooting Star (유성우)", "artistName": "StelLive (스텔라이브)"},
                 {"id": 3, "trackName": "So Many Stars", "artistName": "Maria Kim"}]
                """);
        assertEquals(List.of("스텔라이브"), LrcLibClient.aliasesIn(results, "StelLive"));
        assertEquals(List.of("StelLive", "Kenshi Yonezu"), LrcLibClient.artistNames("StelLive (Kenshi Yonezu)"));
        assertTrue(LrcLibClient.aliasesIn(results, "Nobody").isEmpty());
    }

    @Test
    void splitsOfficialChannelNamesIntoTheArtistNamesTheyCarry() {
        assertEquals(List.of("스텔라이브 StelLive", "스텔라이브", "StelLive"), LrcLibClient.channelNames("스텔라이브 StelLive Official"));
        assertEquals(List.of("米津玄師 Kenshi Yonezu", "米津玄師", "Kenshi Yonezu"), LrcLibClient.channelNames("米津玄師 Kenshi Yonezu"));
        assertEquals(List.of("YOASOBI"), LrcLibClient.channelNames("YOASOBI Official Channel"));
        List<String[]> pairs = LrcLibClient.candidatePairs("Twinkle", "스텔라이브 StelLive Official");
        boolean searched = false;
        for (String[] pair : pairs.subList(0, 3)) searched |= pair[0].equals("스텔라이브") && pair[1].equals("Twinkle");
        assertTrue(searched);
    }

    @Test
    void titleOnlyResultsMustComeFromTheArtistWhenTheChannelOrCatalogNamesIt() throws Exception {
        JsonNode results = MAPPER.readTree("""
                [{"id": 1, "trackName": "Shooting Star (Shooting Star)", "artistName": "N.Flying - Topic", "duration": 200, "syncedLyrics": "[00:10.00] a"},
                 {"id": 2, "trackName": "Shooting Star", "artistName": "StelLive", "duration": 205, "syncedLyrics": "[00:10.00] b"}]
                """);
        AudioTrackInfo topic = new AudioTrackInfo("Shooting Star", "StelLive - Topic", 205_000, "n-NHnzia_Lw", false, "https://www.youtube.com/watch?v=n-NHnzia_Lw");
        JsonNode filtered = LrcLibClient.byArtist(results, topic);
        assertEquals(1, filtered.size());
        assertEquals(2, filtered.get(0).path("id").asInt());
        AudioTrackInfo spotify = new AudioTrackInfo("Shooting Star", "StelLive", 205_000, "x", false, "https://open.spotify.com/track/x");
        assertEquals(1, LrcLibClient.byArtist(results, spotify).size());
        AudioTrackInfo upload = new AudioTrackInfo("Shooting Star", "Some Uploader", 205_000, "y", false, "https://www.youtube.com/watch?v=y");
        assertEquals(0, LrcLibClient.byArtist(results, upload).size());
        AudioTrackInfo named = new AudioTrackInfo("StelLive - Shooting Star", "Some Uploader", 205_000, "z", false, "https://www.youtube.com/watch?v=z");
        assertEquals(2, LrcLibClient.byArtist(results, named).get(0).path("id").asInt());
        AudioTrackInfo official = new AudioTrackInfo("Shooting Star", "스텔라이브 StelLive Official", 205_000, "w", false, "https://www.youtube.com/watch?v=w");
        assertEquals(1, LrcLibClient.byArtist(results, official).size());
        JsonNode onlyOthers = MAPPER.readTree("""
                [{"id": 1, "trackName": "Shooting Star", "artistName": "N.Flying - Topic", "duration": 200, "syncedLyrics": "[00:10.00] a"}]
                """);
        assertNull(LrcLibClient.pickBest(LrcLibClient.byArtist(onlyOthers, topic), 205));
    }

    @Test
    void searchesByTheLongestTitleWordAndKeepsOnlyCloseTitles() throws Exception {
        assertEquals("Heroine", LrcLibClient.keyword("I Wanna Be the Heroine!"));
        assertNull(LrcLibClient.keyword("Heroine"));
        assertNull(LrcLibClient.keyword("夜に駆ける"));
        JsonNode results = MAPPER.readTree("""
                [{"id": 1, "trackName": "I Want to Be a Heroine (히로인이 되고싶어)", "artistName": "StelLive (스텔라이브)", "duration": 194},
                 {"id": 2, "trackName": "Heroine Audition", "artistName": "StelLive (스텔라이브)", "duration": 194}]
                """);
        ArrayNode similar = LrcLibClient.similarTitles(results, "I Wanna Be the Heroine!");
        assertEquals(1, similar.size());
        assertEquals(1, similar.get(0).path("id").asInt());
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
    void treatsLatinOnlyLyricsOfAJapaneseTitleAsATranslationUnlessTheyAreRomaji() throws Exception {
        JsonNode translation = MAPPER.readTree("""
                {"id": 1, "duration": 123.0, "plainLyrics": "We walked along the river at night\\nI never said the words I meant"}""");
        JsonNode original = MAPPER.readTree("""
                {"id": 2, "duration": 123.0, "syncedLyrics": "[00:03.00] 夜の川を歩いた\\n[00:08.00] 言えなかった言葉"}""");
        JsonNode romaji = MAPPER.readTree("""
                {"id": 3, "duration": 123.0, "plainLyrics": "Yoru no kawa wo aruita futari de\\nIenakatta kotoba wo mada oboeteru\\nAshita mo kitto onaji sora no shita"}""");
        assertTrue(LrcLibClient.translated(translation, "夜の川（Night River）/ Sample feat. Singer"));
        assertFalse(LrcLibClient.translated(original, "夜の川（Night River）/ Sample feat. Singer"));
        assertFalse(LrcLibClient.translated(romaji, "夜の川"));
        assertFalse(LrcLibClient.translated(translation, "Night River"));

        ArrayNode results = MAPPER.createArrayNode().add(translation).add(original);
        assertEquals(2, LrcLibClient.pickBest(results, 123, "夜の川").path("id").asInt());
    }

    @Test
    void triesTheEnglishTitleInParenthesesAndPutsTheChannelArtistFirst() {
        List<String[]> pairs = LrcLibClient.candidatePairs("夜の川（Night River）/ Sample feat. Singer", "Sample");
        assertTrue(hasPair(pairs, "Sample", "Night River"));
        int plain = -1;
        for (int i = 0; i < pairs.size(); i++) {
            if (pairs.get(i)[0].equals("Sample") && pairs.get(i)[1].equals("夜の川")) plain = i;
        }
        assertTrue(plain >= 0 && plain < 6);
        assertFalse(hasPair(LrcLibClient.candidatePairs("夜の川 (feat. Singer)", "Sample"), "Sample", "feat. Singer"));
    }

    @Test
    void stopsWaitingForASlowAiAnswerOnceTheLookupBudgetIsSpent() {
        AudioTrackInfo info = new AudioTrackInfo("Song", "Channel", 1000, "id", false, "https://youtu.be/id");
        long started = System.currentTimeMillis();
        assertNull(LrcLibClient.awaitResolved(new CompletableFuture<>(), started - LrcLibClient.LOOKUP_BUDGET_MS, info));
        long waited = System.currentTimeMillis() - started;
        assertTrue(waited < 5_000, "waited " + waited + "ms");

        Lyrics ready = new Lyrics("Song", "Artist", "line", List.of(), false);
        assertEquals(ready, LrcLibClient.awaitResolved(CompletableFuture.completedFuture(ready), started, info));
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
