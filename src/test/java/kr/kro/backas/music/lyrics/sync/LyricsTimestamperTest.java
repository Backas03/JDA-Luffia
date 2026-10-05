package kr.kro.backas.music.lyrics.sync;

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

    private static List<WhisperClient.Word> heard(List<Integer> skip, long startMs) {
        List<WhisperClient.Word> words = new ArrayList<>();
        long at = startMs;
        for (int i = 0; i < LINES.size(); i++) {
            if (!skip.contains(i)) {
                for (String word : LINES.get(i).split(" ")) {
                    words.add(new WhisperClient.Word(word, at, at + 250));
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
        assertEquals(5_000, result.get().lines().get(0).timeMs());
        assertTrue(result.get().lines().get(1).timeMs() > result.get().lines().get(0).timeMs());
        assertTrue(result.get().lines().get(3).timeMs() > result.get().lines().get(2).timeMs());
    }

    @Test
    void fillsUnheardLinesBetweenTheirNeighbours() {
        List<String> five = new ArrayList<>(LINES);
        five.add(2, "a line the singer swallowed");
        List<WhisperClient.Word> words = new ArrayList<>();
        long at = 5_000;
        for (int i = 0; i < five.size(); i++) {
            if (i != 2) {
                for (String word : five.get(i).split(" ")) {
                    words.add(new WhisperClient.Word(word, at, at + 250));
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
