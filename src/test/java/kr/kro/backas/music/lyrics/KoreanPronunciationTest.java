package kr.kro.backas.music.lyrics;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KoreanPronunciationTest {

    @Test
    void readsLyricsTheWayTheyAreSung() {
        assertEquals("아아 코이노", KoreanPronunciation.of("あー恋の"));
        assertEquals("요루노 마치오 히토리데 아루이타", KoreanPronunciation.of("夜の街を一人で歩いた"));
        assertEquals("츠메타이 카제가 호오오 나데루", KoreanPronunciation.of("冷たい風が頬を撫でる"));
        assertEquals("코이시테루 토오쿄오노 소라와", KoreanPronunciation.of("恋してる 東京の空は"));
        assertEquals("도코에 이쿠노", KoreanPronunciation.of("どこへ行くの"));
    }

    @Test
    void keepsParticlesAndEndingsOnTheirWord() {
        assertEquals("키미오 아이시테루", KoreanPronunciation.of("君を愛してる"));
        assertEquals("아이시테이마스", KoreanPronunciation.of("愛しています"));
        assertEquals("코코로노 나카데", KoreanPronunciation.of("心の中で"));
        assertEquals("미츠메테이타이", KoreanPronunciation.of("見つめていたい"));
        assertEquals("Baby 콘야와 카에사나이", KoreanPronunciation.of("Baby 今夜は帰さない"));
        assertEquals("Oh baby 코이시찻탄다", KoreanPronunciation.of("Oh baby 恋しちゃったんだ"));
        assertEquals("히토리키리노 요루", KoreanPronunciation.of("一人きりの夜"));
        assertEquals("티이캇푸", KoreanPronunciation.of("ティーカップ"));
    }

    @Test
    void writesSmallTsuAndNAsBatchimAndLongVowelsAsAnExtraVowel() {
        assertEquals("앗", KoreanPronunciation.of("あっ"));
        assertEquals("하싯테이쿠", KoreanPronunciation.of("走っていく"));
        assertEquals("판타지이", KoreanPronunciation.of("ファンタジー"));
        assertEquals("아리가토오", KoreanPronunciation.of("ありがとう"));
        assertEquals("응~", KoreanPronunciation.of("ん〜"));
    }

    @Test
    void dropsBracketsAndKeepsSentencePunctuation() {
        assertEquals("사요나라토 잇타", KoreanPronunciation.of("「さよなら」と言った"));
        assertEquals("와타시와 코코니 이루!", KoreanPronunciation.of("わたしは、ここにいる！"));
    }

    @Test
    void skipsLinesWithoutJapanese() {
        assertNull(KoreanPronunciation.of("Hold my hand"));
        assertNull(KoreanPronunciation.of("사랑해"));
        assertNull(KoreanPronunciation.of(""));
        assertNull(KoreanPronunciation.of(null));
    }

    @Test
    void appliesOnlyToJapaneseSongs() {
        assertTrue(KoreanPronunciation.appliesTo(List.of(new LyricLine(0, "君の声が聞こえた"), new LyricLine(1000, "Baby I love you"))));
        assertFalse(KoreanPronunciation.appliesTo(List.of(new LyricLine(0, "我爱你"), new LyricLine(1000, "月亮代表我的心"))));
        assertFalse(KoreanPronunciation.appliesTo(List.of(new LyricLine(0, "Hold my hand"))));
    }
}
