package kr.kro.backas.music.lyrics.sync;

import kr.kro.backas.music.lyrics.LyricLine;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LyricsAlignerTest {

    private static final List<LyricLine> ENGLISH = List.of(
            new LyricLine(4_000, "Hello darkness my old friend"),
            new LyricLine(8_000, "I've come to talk with you again"),
            new LyricLine(12_000, "Because a vision softly creeping"),
            new LyricLine(16_000, "Left its seeds while I was sleeping"));

    private static List<WhisperClient.Word> sung(List<LyricLine> lines, long shiftMs) {
        List<WhisperClient.Word> words = new ArrayList<>();
        for (LyricLine line : lines) {
            long at = line.timeMs() + shiftMs;
            for (String word : line.text().split(" ")) {
                words.add(new WhisperClient.Word(word, at, at + 250));
                at += 300;
            }
        }
        return words;
    }

    @Test
    void findsHowMuchLaterTheVocalsStartThanTheLyricsSay() {
        Optional<LyricsAligner.Alignment> result = LyricsAligner.align(sung(ENGLISH, 2_500), ENGLISH, 60_000);
        assertTrue(result.isPresent());
        assertEquals(-2_500, result.get().offsetMs());
        assertEquals(4, result.get().matchedLines());
    }

    @Test
    void keepsTheLyricsFileTimingWhenWhisperDisagreesByLessThanItsOwnErrorBand() {
        Optional<LyricsAligner.Alignment> result = LyricsAligner.align(sung(ENGLISH, 400), ENGLISH, 60_000);
        assertTrue(result.isPresent());
        assertEquals(0, result.get().offsetMs());
        assertEquals(4, result.get().matchedLines());
        assertEquals(-900, LyricsAligner.align(sung(ENGLISH, 900), ENGLISH, 60_000).get().offsetMs());
    }

    @Test
    void worksForJapaneseLyricsWhereWhisperSplitsDifferently() {
        List<LyricLine> lines = List.of(
                new LyricLine(5_000, "君の知らない物語を"),
                new LyricLine(9_000, "いつか話せる日が来るまで"),
                new LyricLine(13_000, "星空の下で待っている"));
        List<WhisperClient.Word> words = List.of(
                new WhisperClient.Word("君の", 3_500, 3_800), new WhisperClient.Word("知らない", 3_800, 4_300),
                new WhisperClient.Word("物語を", 4_300, 4_900),
                new WhisperClient.Word("いつか", 7_500, 7_900), new WhisperClient.Word("話せる", 7_900, 8_300),
                new WhisperClient.Word("日が", 8_300, 8_600), new WhisperClient.Word("来るまで", 8_600, 9_100),
                new WhisperClient.Word("星空の", 11_500, 12_000), new WhisperClient.Word("下で", 12_000, 12_300),
                new WhisperClient.Word("待っている", 12_300, 12_900));
        Optional<LyricsAligner.Alignment> result = LyricsAligner.align(words, lines, 60_000);
        assertTrue(result.isPresent());
        assertEquals(1_500, result.get().offsetMs());
    }

    @Test
    void refusesWhenTheTranscriptDoesNotMatchTheLyrics() {
        List<WhisperClient.Word> noise = List.of(
                new WhisperClient.Word("completely", 1_000, 1_300), new WhisperClient.Word("unrelated", 1_300, 1_700),
                new WhisperClient.Word("chatter", 1_700, 2_000), new WhisperClient.Word("about", 5_000, 5_200),
                new WhisperClient.Word("weather", 5_200, 5_600), new WhisperClient.Word("forecasts", 5_600, 6_100));
        assertTrue(LyricsAligner.align(noise, ENGLISH, 60_000).isEmpty());
        assertTrue(LyricsAligner.align(List.of(), ENGLISH, 60_000).isEmpty());
    }

    @Test
    void dropsASingleOutlierButRefusesWhenLinesDisagree() {
        List<WhisperClient.Word> words = new ArrayList<>(sung(ENGLISH, 2_000));
        for (int i = 0; i < words.size(); i++) {
            WhisperClient.Word word = words.get(i);
            if (word.startMs() >= 18_000) words.set(i, new WhisperClient.Word(word.text(), word.startMs() + 3_000, word.endMs() + 3_000));
        }
        Optional<LyricsAligner.Alignment> result = LyricsAligner.align(words, ENGLISH, 60_000);
        assertTrue(result.isPresent());
        assertEquals(-2_000, result.get().offsetMs());
        assertEquals(3, result.get().matchedLines());

        List<LyricLine> three = ENGLISH.subList(0, 3);
        List<WhisperClient.Word> spread = new ArrayList<>(sung(three, 1_000));
        for (int i = 0; i < spread.size(); i++) {
            WhisperClient.Word word = spread.get(i);
            if (word.startMs() >= 13_000) spread.set(i, new WhisperClient.Word(word.text(), word.startMs() + 2_000, word.endMs() + 2_000));
        }
        assertTrue(LyricsAligner.align(spread, three, 60_000).isEmpty());
    }

    @Test
    void onlyExpectsMatchesForLinesThatFallInsideTheCapturedAudio() {
        List<WhisperClient.Word> heard = new ArrayList<>();
        for (WhisperClient.Word word : sung(ENGLISH, 1_000)) {
            if (word.startMs() < 14_000) heard.add(word);
        }
        Optional<LyricsAligner.Alignment> result = LyricsAligner.align(heard, ENGLISH, 14_000);
        assertTrue(result.isPresent());
        assertEquals(3, result.get().matchedLines());
        assertEquals(-1_000, result.get().offsetMs());
    }

    @Test
    void acceptsTwoAgreeingLinesWhenTheVocalsStartLateInTheCapture() {
        List<WhisperClient.Word> heard = new ArrayList<>();
        for (WhisperClient.Word word : sung(ENGLISH, 13_000)) {
            if (word.startMs() < 24_000) heard.add(word);
        }
        Optional<LyricsAligner.Alignment> result = LyricsAligner.align(heard, ENGLISH, 25_000);
        assertTrue(result.isPresent());
        assertEquals(-13_000, result.get().offsetMs());
        assertEquals(2, result.get().matchedLines());
    }

    @Test
    void normalisationFoldsWidthCaseKanaAndPunctuation() {
        assertEquals("helloworld", LyricsAligner.normalize("Ｈｅｌｌｏ, World!"));
        assertEquals("かたかな", LyricsAligner.normalize("カタカナ"));
        assertEquals("", LyricsAligner.normalize("♪ ... ♪"));
        assertEquals(1.0, LyricsAligner.similarity("abcd", "abcd"));
        assertTrue(LyricsAligner.similarity("abcd", "wxyz") < 0.1);
    }

    @Test
    void firstWordOnsetIgnoresLeadingSilenceWhisperAttachedToTheWord() {
        assertEquals(41_660, LyricsAligner.onsetMs(new WhisperClient.Word("君", 40_980, 41_980)));
        assertEquals(46_860, LyricsAligner.onsetMs(new WhisperClient.Word("ただ", 46_860, 47_360)));
        assertEquals(1_000, LyricsAligner.onsetMs(new WhisperClient.Word("hello", 1_000, 1_400)));
        assertEquals(2_450, LyricsAligner.onsetMs(new WhisperClient.Word("hello", 1_000, 3_000)));
        assertEquals(LyricsAligner.MIN_WORD_MS, LyricsAligner.plausibleDurationMs("a"));
    }

    @Test
    void guessesTheLanguageFromTheScript() {
        assertEquals("ja", LyricsAligner.languageHint(List.of(new LyricLine(0, "君の知らない物語"))));
        assertEquals("ko", LyricsAligner.languageHint(List.of(new LyricLine(0, "너의 이름은"))));
        assertEquals("en", LyricsAligner.languageHint(ENGLISH));
        assertNull(LyricsAligner.languageHint(List.of(new LyricLine(0, "月亮代表我的心"))));
    }
}
