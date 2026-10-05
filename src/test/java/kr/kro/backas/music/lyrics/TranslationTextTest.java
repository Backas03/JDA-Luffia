package kr.kro.backas.music.lyrics;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class TranslationTextTest {

    @Test
    void combinesAReadingWithTheTranslationAndSplitsThemBackApart() {
        String value = TranslationText.combine("아오이 소라", "푸른 하늘");
        assertEquals("푸른 하늘", TranslationText.translation(value));
        assertEquals("아오이 소라", TranslationText.reading(value));
        assertEquals(List.of("*(아오이 소라)*", "푸른 하늘"), TranslationText.displayLines(value, 300));
        assertEquals("-# *(아오이 소라)*\n-# 푸른 하늘", TranslationText.markdown(value, 300));
    }

    @Test
    void plainTranslationsStayUntouched() {
        assertEquals("푸른 하늘", TranslationText.combine("", "푸른 하늘"));
        assertEquals("푸른 하늘", TranslationText.combine(null, "푸른 하늘"));
        assertNull(TranslationText.reading("푸른 하늘"));
        assertEquals("푸른 하늘", TranslationText.translation("푸른 하늘"));
        assertEquals("-# 푸른 하늘", TranslationText.markdown("푸른 하늘", 300));
        assertEquals("", TranslationText.markdown(null, 300));
    }

    @Test
    void readingsMustBeHangulAndLoseWrappingParentheses() {
        assertEquals("아오이 소라", TranslationText.cleanReading(" (아오이  소라) "));
        assertEquals("", TranslationText.cleanReading("aoi sora"));
        assertEquals("", TranslationText.cleanReading("青い空"));
        assertEquals("푸른 하늘", TranslationText.combine("aoi sora", "푸른 하늘"));
    }
}
