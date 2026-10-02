package kr.kro.backas.music.lyrics;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LyricsRepetitionTest {

    @Test
    void restoresDroppedRepeatedWord() {
        assertEquals("네 네 네", LyricsRepetition.match("はい はい はい", "네 네"));
        assertEquals("네, 네, 네!", LyricsRepetition.match("はい、はい、はい！", "네, 네!"));
    }

    @Test
    void trimsExtraRepeatedWord() {
        assertEquals("좋아 좋아", LyricsRepetition.match("好き 好き", "좋아 좋아 좋아 좋아"));
    }

    @Test
    void expandsSingleWordWhenWholeLineRepeats() {
        assertEquals("네 네 네!", LyricsRepetition.match("はい はい はい", "네!"));
    }

    @Test
    void fixesRepeatedWordInsideLongerLine() {
        assertEquals("저기 저기 저기 들어봐", LyricsRepetition.match("ねえ ねえ ねえ 聞いて", "저기 저기 들어봐"));
    }

    @Test
    void fixesGluedCharacterRun() {
        assertEquals("아아아아아아", LyricsRepetition.match("ああああああ", "아아아아아"));
        assertEquals("와아아아!", LyricsRepetition.match("わーーー！", "와아아!"));
    }

    @Test
    void leavesMatchingAndUnrelatedLinesAlone() {
        assertEquals("미워 미워 미워", LyricsRepetition.match("憎い 憎い 憎い", "미워 미워 미워"));
        assertEquals("너의 목소리가 들려", LyricsRepetition.match("君の声が聞こえる", "너의 목소리가 들려"));
        assertEquals("좋아해 정말로", LyricsRepetition.match("好き 好き 好き", "좋아해 정말로"));
        assertEquals("하하 웃어 웃어", LyricsRepetition.match("笑って 笑って", "하하 웃어 웃어"));
    }

    @Test
    void matchesCommaSeparatedPhrasesInBothDirections() {
        assertEquals("사랑해 사랑해 사랑해", LyricsRepetition.match("Ti amo, je t'aime, ich liebe dich", "사랑해 사랑해 사랑해 사랑해"));
        assertEquals("사랑해, 사랑해, 사랑해", LyricsRepetition.match("Ti amo, je t'aime, ich liebe dich", "사랑해, 사랑해"));
        assertEquals("사랑해, 사랑해, 사랑해", LyricsRepetition.match("Ti amo, je t'aime, ich liebe dich", "사랑해, 사랑해, 사랑해"));
    }

    @Test
    void raisesTheCountToTheNumberOfLanguagesInALine() {
        assertEquals("사랑해 사랑해 사랑해", LyricsRepetition.match("Te amo 사랑해 I love you", "사랑해 사랑해"));
        assertEquals("사랑해 사랑해", LyricsRepetition.match("Я люблю тебя 我爱你", "사랑해 사랑해"));
        assertEquals("좋아 좋아 좋아", LyricsRepetition.match("好き 好き I love you", "좋아 좋아"));
        assertEquals("좋아 좋아 좋아", LyricsRepetition.match("好き 好き I love you", "좋아 좋아 좋아"));
    }

    @Test
    void neverLowersACountItCannotBeSureAbout() {
        assertEquals("사랑해 사랑해 사랑해", LyricsRepetition.match("Te amo I love you 사랑해", "사랑해 사랑해 사랑해"));
        assertEquals("그만해", LyricsRepetition.match("まじで STOP", "그만해"));
        assertEquals("진짜 STOP, 그만해", LyricsRepetition.match("まじで STOP", "진짜 STOP, 그만해"));
        assertEquals("사랑해 사랑해 사랑해", LyricsRepetition.match("Ti amo je t'aime ich liebe dich", "사랑해 사랑해 사랑해"));
    }

    @Test
    void ignoresAmbiguousSources() {
        assertEquals("라라 좋아 좋아", LyricsRepetition.match("ララ ララ 好き 好き 好き", "라라 좋아 좋아"));
        assertEquals("", LyricsRepetition.match("はい はい", ""));
    }
}
