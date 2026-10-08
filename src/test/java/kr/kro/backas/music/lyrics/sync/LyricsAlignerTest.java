package kr.kro.backas.music.lyrics.sync;

import kr.kro.backas.music.lyrics.LyricLine;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
    void reportsSmallShiftsAsMeasuredAndLeavesTheTrustDecisionToThePolicy() {
        Optional<LyricsAligner.Alignment> result = LyricsAligner.align(sung(ENGLISH, 400), ENGLISH, 60_000);
        assertTrue(result.isPresent());
        assertEquals(-400, result.get().offsetMs());
        assertEquals(4, result.get().matchedLines());
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

    private static final List<LyricLine> REPEATING_INTRO = List.of(
            new LyricLine(0, ""),
            new LyricLine(1_080, "ねえ"),
            new LyricLine(1_750, "ふわふわ　ゆらゆら　ねえきいて？"),
            new LyricLine(4_250, "どきどき　ぐるぐる　まわるまわる"),
            new LyricLine(6_740, "ふわふわ　ゆらゆら　ねえきいて？"),
            new LyricLine(9_170, "どきどき　ぐるぐる　まわるまわる"),
            new LyricLine(11_090, "あの日の空を覚えているかな"),
            new LyricLine(13_440, "二人で歩いた坂道の先"),
            new LyricLine(16_000, "小さな声で名前を呼んだ"),
            new LyricLine(18_540, "夕焼け色に　染まる街角"),
            new LyricLine(21_160, "遠くの汽笛が　聞こえた気がした"),
            new LyricLine(23_660, "明日になれば　何か変わるかな"),
            new LyricLine(26_380, "冷たい風が　頬をかすめて"),
            new LyricLine(28_490, "それでも僕は　ここで待ってる"),
            new LyricLine(31_430, "光の中で　手を伸ばして"),
            new LyricLine(33_910, "君の名前を　もう一度呼ぶよ"),
            new LyricLine(36_570, "忘れないでと　笑ってみせた"),
            new LyricLine(38_960, "星を数えて　夜を越えて"),
            new LyricLine(40_170, "月を見上げて　朝を待って"),
            new LyricLine(41_460, "揺れる心は　まだ止まらない"),
            new LyricLine(43_910, "言葉にならない　想いを抱いて"),
            new LyricLine(45_190, "眠れないまま　窓を開けたら"),
            new LyricLine(46_550, "知らない歌が　流れてきたんだ"),
            new LyricLine(48_930, "星を数えて　夜を越えて"),
            new LyricLine(50_210, "月を見上げて　朝を待って"),
            new LyricLine(51_410, "震える指で　扉を叩く"),
            new LyricLine(53_920, "言葉にならない　想いを抱いて"),
            new LyricLine(55_190, "眠れないまま　窓を開けたら"),
            new LyricLine(56_440, "最後の歌を　君に届ける"),
            new LyricLine(59_160, "そうだね…"),
            new LyricLine(60_450, "ふわふわ　ゆらゆら　ねえきいて？"),
            new LyricLine(62_940, "どきどき　ぐるぐる　まわるまわる"),
            new LyricLine(65_440, "ふわふわ　ゆらゆら　ねえきいて？"),
            new LyricLine(68_000, "どきどき　ぐるぐる　まわるまわる"),
            new LyricLine(69_780, "あの日の空を覚えているかな"),
            new LyricLine(72_120, "二人で歩いた坂道の先"),
            new LyricLine(74_840, "雨上がりの道　水たまり跳ねて"),
            new LyricLine(77_260, "傘を閉じたら　虹が見えたよ"),
            new LyricLine(79_720, "ありがとうって　言えなかったけど"),
            new LyricLine(82_130, "今ならきっと　伝えられるはず"),
            new LyricLine(85_160, "季節が巡って　また春が来て"),
            new LyricLine(87_390, "僕らはずっと　ここにいるから"));

    private static List<WhisperClient.Word> heard(List<LyricLine> lines, long shiftMs, long untilMs, Map<String, String> misheard,
                                                  long misheardBeforeMs) {
        List<WhisperClient.Word> words = new ArrayList<>();
        for (LyricLine line : lines) {
            if (line.timeMs() + shiftMs >= untilMs) break;
            String text = line.timeMs() < misheardBeforeMs ? misheard.getOrDefault(line.text(), line.text()) : line.text();
            long at = line.timeMs() + shiftMs;
            for (String word : text.split("[　 ]")) {
                if (word.isEmpty()) continue;
                words.add(new WhisperClient.Word(word, at, at + 250));
                at += 300;
            }
        }
        return words;
    }

    @Test
    void doesNotLockOntoALaterRepeatOfTheIntroThatWhisperHeardMoreClearly() {
        Map<String, String> misheard = Map.of(
                "ふわふわ　ゆらゆら　ねえきいて？", "フワフワ揺ら揺ら　ねえ聞いて",
                "どきどき　ぐるぐる　まわるまわる", "ドキドキぐるぐる　回る回る");
        Optional<LyricsAligner.Alignment> result = LyricsAligner.align(heard(REPEATING_INTRO, 0, 90_000, misheard, 10_000), REPEATING_INTRO, 90_000);
        assertTrue(result.isPresent());
        assertEquals(0, result.get().offsetMs());

        Optional<LyricsAligner.Alignment> late = LyricsAligner.align(heard(REPEATING_INTRO, 2_500, 90_000, misheard, 10_000), REPEATING_INTRO, 90_000);
        assertTrue(late.isPresent());
        assertEquals(-2_500, late.get().offsetMs());
    }

    @Test
    void refusesWhenARepeatedSectionFitsTwoOffsetsEqually() {
        List<LyricLine> lines = new ArrayList<>();
        for (int pass = 0; pass < 2; pass++) {
            for (int i = 0; i < 3; i++) lines.add(new LyricLine(2_000 + 28_000L * pass + 4_000L * i, ENGLISH.get(i).text()));
        }
        assertTrue(LyricsAligner.align(sung(lines.subList(3, 6), 0), lines, 45_000).isEmpty());
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
