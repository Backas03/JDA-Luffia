package kr.kro.backas.music.lyrics.sync;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LyricsConverterTest {

    private static WhisperClient.Segment segment(String text) {
        return new WhisperClient.Segment(0, 1_000, text);
    }

    @Test
    void tellsWhichLanguageTheVocalsAreSungIn() {
        assertEquals("ko", LyricsConverter.spokenLanguage(List.of(segment("움츠러든 어깨를 따라서"), segment("다시 저물어가는 오늘의 끝"))));
        assertEquals("ja", LyricsConverter.spokenLanguage(List.of(segment("苦しみは無標的だ"), segment("何処からでもない自意識だ"))));
        assertNull(LyricsConverter.spokenLanguage(List.of(segment("오늘"))));
        assertNull(LyricsConverter.spokenLanguage(List.of(segment("ご視聴ありがとうございました"), segment("チャンネル登録よろしくお願いします"))));
    }

    @Test
    void onlyJapaneseAgainstKoreanCountsAsAMismatch() {
        assertTrue(LyricsConverter.mismatched("ja", "ko"));
        assertTrue(LyricsConverter.mismatched("ko", "ja"));
        assertFalse(LyricsConverter.mismatched("ja", "ja"));
        assertFalse(LyricsConverter.mismatched("en", "ko"));
        assertFalse(LyricsConverter.mismatched("ja", null));
        assertFalse(LyricsConverter.mismatched(null, "ko"));
    }
}
