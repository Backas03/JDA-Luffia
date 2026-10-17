package kr.kro.backas.music.lyrics.sync;

import kr.kro.backas.music.lyrics.LyricLine;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LyricsTimestamperTest {

    private static final List<String> LINES = List.of(
            "blue kettle whistles on the stove",
            "paper boats drift down the gutter",
            "the lamp post hums a tired tune",
            "we count the cracks along the road");

    private static List<AsrClient.Word> heard(List<Integer> skip, long startMs) {
        List<AsrClient.Word> words = new ArrayList<>();
        long at = startMs;
        for (int i = 0; i < LINES.size(); i++) {
            if (!skip.contains(i)) {
                for (String word : LINES.get(i).split(" ")) {
                    words.add(new AsrClient.Word(word, at, at + 250));
                    at += 300;
                }
            }
            at += 2_000;
        }
        return words;
    }

    @Test
    void assignsEachLineTheTimeItsFirstWordWasHeard() {
        Optional<LyricsTimestamper.Result> result = LyricsTimestamper.timestamp(LINES, heard(List.of(), 5_000), 60_000);
        assertTrue(result.isPresent());
        assertEquals(4, result.get().matched());
        assertEquals(5_000 - LyricsTimestamper.LINE_LEAD_MS, result.get().lines().get(0).timeMs());
        assertTrue(result.get().lines().get(1).timeMs() > result.get().lines().get(0).timeMs());
        assertTrue(result.get().lines().get(3).timeMs() > result.get().lines().get(2).timeMs());
    }

    @Test
    void fillsUnheardLinesBetweenTheirNeighbours() {
        List<String> five = new ArrayList<>(LINES);
        five.add(2, "a line the singer swallowed");
        List<AsrClient.Word> words = new ArrayList<>();
        long at = 5_000;
        for (int i = 0; i < five.size(); i++) {
            if (i != 2) {
                for (String word : five.get(i).split(" ")) {
                    words.add(new AsrClient.Word(word, at, at + 250));
                    at += 300;
                }
            }
            at += 2_000;
        }
        Optional<LyricsTimestamper.Result> result = LyricsTimestamper.timestamp(five, words, 60_000);
        assertTrue(result.isPresent());
        assertEquals(4, result.get().matched());
        long before = result.get().lines().get(1).timeMs();
        long filled = result.get().lines().get(2).timeMs();
        long after = result.get().lines().get(3).timeMs();
        assertTrue(before < filled && filled < after, before + " " + filled + " " + after);
    }

    private static List<AsrClient.Word> sung(List<String> lines, List<Long> startsMs) {
        List<AsrClient.Word> words = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            if (startsMs.get(i) == null) continue;
            long at = startsMs.get(i);
            for (String word : lines.get(i).split(" ")) {
                words.add(new AsrClient.Word(word, at, at + 250));
                at += 300;
            }
        }
        return words;
    }

    @Test
    void aRepeatedLineHeardOnceDoesNotClaimItsTwinsSpot() {
        List<String> lines = List.of(
                "blue kettle whistles on the stove",
                "paper boats drift down the gutter",
                "love me love me doki doki",
                "love me love me doki doki",
                "the lamp post hums a tired tune",
                "we count the cracks along the road");
        List<Long> starts = java.util.Arrays.asList(5_000L, 10_000L, 15_000L, null, 30_000L, 35_000L);
        List<LyricLine> timed = lyricLines(LyricsTimestamper.timestamp(lines, sung(lines, starts), 60_000).orElseThrow());
        assertTrue(timed.get(3).timeMs() > 20_000, "second repeat at " + timed.get(3).timeMs());
    }

    private static List<LyricLine> lyricLines(LyricsTimestamper.Result result) {
        List<LyricLine> lines = new ArrayList<>();
        for (LyricLine line : result.lines()) {
            if (!line.text().isBlank()) lines.add(line);
        }
        return lines;
    }

    private static List<String> texts(List<LyricLine> lines) {
        List<String> texts = new ArrayList<>();
        for (LyricLine line : lines) texts.add(line.text());
        return texts;
    }

    @Test
    void marksALongInstrumentalBreakWithARestAfterTheLastWordBeforeIt() {
        List<Long> starts = java.util.Arrays.asList(5_000L, 10_000L, 40_000L, 45_000L);
        LyricsTimestamper.Result result = LyricsTimestamper.timestamp(LINES, sung(LINES, starts), 60_000).orElseThrow();
        assertEquals(List.of(LINES.get(0), LINES.get(1), "", LINES.get(2), LINES.get(3), ""), texts(result.lines()));
        assertEquals(11_750 + LyricsTimestamper.REST_DELAY_MS, result.lines().get(2).timeMs());
        assertEquals(4, result.matched());
        assertEquals(4, result.total());
    }

    @Test
    void restsGoOnlyWhereTheAccompanimentPlaysAlone() {
        short[] samples = VocalActivityTest.silence(30, 7);
        VocalActivityTest.sing(samples, 1_000, 10_000, 220, 60);
        VocalActivityTest.drums(samples, 11_000, 20_000, 500, 3);
        VocalActivityTest.sing(samples, 20_000, 5_000, 260, 60);
        VocalActivityTest.drums(samples, 25_000, 30_000, 500, 4);
        VocalActivity vocals = VocalActivity.of(VocalActivityTest.wav(samples));
        List<LyricLine> lines = List.of(
                new LyricLine(1_000, "held note"),
                new LyricLine(6_000, ""),
                new LyricLine(20_000, "after the break"));
        List<LyricLine> placed = LyricsTimestamper.placeRests(lines, vocals);
        assertEquals(List.of("held note", "", "after the break", ""), texts(placed));
        assertTrue(placed.get(1).timeMs() >= 10_800 && placed.get(1).timeMs() <= 11_600, "rest at " + placed.get(1).timeMs());
        assertTrue(placed.get(3).timeMs() >= 24_800 && placed.get(3).timeMs() <= 25_600, "outro at " + placed.get(3).timeMs());
    }

    @Test
    void withoutAudioOnlyRestsTooShortToNoticeAreDropped() {
        List<LyricLine> lines = List.of(
                new LyricLine(1_000, "first"),
                new LyricLine(4_000, ""),
                new LyricLine(7_000, "second"),
                new LyricLine(9_000, ""),
                new LyricLine(20_000, "third"));
        assertEquals(List.of("first", "second", "", "third"), texts(LyricsTimestamper.placeRests(lines, null)));
    }

    @Test
    void partialPassesKeepOneLinePerLyricSoTheFinalPassCanReplaceThem() {
        List<Long> starts = java.util.Arrays.asList(5_000L, 10_000L, 40_000L, 45_000L);
        LyricsTimestamper.Result result = LyricsTimestamper.timestamp(LINES, sung(LINES, starts), 60_000, true).orElseThrow();
        assertEquals(LINES, texts(result.lines()));
    }

    private static AsrClient.Word word(String text, long startMs, long endMs) {
        return new AsrClient.Word(text, startMs, endMs);
    }

    @Test
    void aLineSmearedOverTheSilenceBeforeItStartsWhereItsWordsSettle() {
        List<AsrClient.Word> words = new ArrayList<>(sung(List.of(LINES.get(0)), List.of(5_000L)));
        words.add(word("paper", 8_000, 14_000));
        words.add(word("boats", 14_000, 19_000));
        words.add(word("drift", 19_500, 19_800));
        words.add(word("down", 19_800, 20_100));
        words.add(word("the", 20_100, 20_400));
        words.add(word("gutter", 20_400, 20_900));
        words.addAll(sung(LINES.subList(2, 4), List.of(25_000L, 30_000L)));
        List<LyricLine> timed = lyricLines(LyricsTimestamper.timestamp(LINES, words, 60_000).orElseThrow());
        assertEquals(19_500 - LyricsAligner.plausibleDurationMs("paperboats") - LyricsTimestamper.LINE_LEAD_MS, timed.get(1).timeMs());
    }

    @Test
    void stretchedWordsRightAfterThePreviousLineKeepTheirOwnOnset() {
        List<AsrClient.Word> words = new ArrayList<>(sung(List.of(LINES.get(0)), List.of(5_000L)));
        words.add(word("paper", 6_750, 12_000));
        words.add(word("boats", 12_000, 17_000));
        words.add(word("drift", 17_500, 17_800));
        words.add(word("down", 17_800, 18_100));
        words.add(word("the", 18_100, 18_400));
        words.add(word("gutter", 18_400, 18_900));
        words.addAll(sung(LINES.subList(2, 4), List.of(25_000L, 30_000L)));
        List<LyricLine> timed = lyricLines(LyricsTimestamper.timestamp(LINES, words, 60_000).orElseThrow());
        assertEquals(LyricsAligner.onsetMs(words.get(6)) - LyricsTimestamper.LINE_LEAD_MS, timed.get(1).timeMs());
    }

    @Test
    void aLineDoesNotStartOnWordsThePreviousLineAlreadyCovered() {
        List<Integer> tokenIndex = List.of(0, 4, 10, 15);
        String text = "bluekettlepaperboats";
        assertEquals(2, LyricsTimestamper.skipSharedStart(text, tokenIndex, 1, 20, 10, "paperboats"));
        assertEquals(1, LyricsTimestamper.skipSharedStart(text, tokenIndex, 1, 20, 4, "paperboats"));
    }

    @Test
    void aLateMatchThatSqueezesTheNextLinesIsDropped() {
        List<String> lines = List.of(
                "blue kettle whistles on the stove",
                "paper boats drift down the gutter",
                "the lamp post hums a tired tune",
                "we count the cracks along the road",
                "silver rain taps the window glass",
                "morning trains rattle past the station",
                "golden fields sway beneath the sun",
                "quiet harbors wait for evening tide");
        List<Long> starts = java.util.Arrays.asList(5_000L, 10_000L, 50_000L, null, null, 51_000L, 55_000L, 58_000L);
        List<LyricLine> timed = lyricLines(LyricsTimestamper.timestamp(lines, sung(lines, starts), 70_000).orElseThrow());
        assertTrue(timed.get(2).timeMs() < 30_000, "squeezed line at " + timed.get(2).timeMs());
        assertTrue(timed.get(4).timeMs() - timed.get(3).timeMs() >= 2_000, timed.get(3).timeMs() + " " + timed.get(4).timeMs());
    }

    @Test
    void givesUpWhenFewerThanHalfTheLinesAreHeard() {
        assertTrue(LyricsTimestamper.timestamp(LINES, heard(List.of(0, 1, 2), 5_000), 60_000).isEmpty());
        assertTrue(LyricsTimestamper.timestamp(LINES, List.of(), 60_000).isEmpty());
    }

    @Test
    void dropsBlankLinesAndSectionTags() {
        List<String> usable = LyricsTimestamper.usableLines(java.util.Arrays.asList("", "[Chorus]", " first ", "(Bridge)", "second", null));
        assertEquals(List.of("first", "second"), usable);
    }
}
