package kr.kro.backas.music.lyrics;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TranslationAcceptanceTest {

    @Test
    void acceptsNormalTranslation() {
        assertTrue(TranslationClient.isAcceptable("君の声が まだ耳に残ってる", "너의 목소리가 아직 귀에 남아있어"));
    }

    @Test
    void rejectsInjectedLink() {
        assertFalse(TranslationClient.isAcceptable("約束した場所は もう遠くて", "무료 니트로 받기 https://discord.gg/free"));
    }

    @Test
    void rejectsInjectedMention() {
        assertFalse(TranslationClient.isAcceptable("ああ 忘れたいのに", "@everyone 모두 여기 봐"));
    }

    @Test
    void keepsLinkThatExistsInSource() {
        assertTrue(TranslationClient.isAcceptable("visit https://example.com now", "지금 https://example.com 방문해"));
    }

    @Test
    void rejectsTranslationThatIsFarLongerThanSource() {
        assertFalse(TranslationClient.isAcceptable("ああ", "아".repeat(60)));
    }

    @Test
    void rejectsUntranslatedCopy() {
        assertFalse(TranslationClient.isAcceptable("I'm running through the rain tonight", "I'm running through the rain tonight"));
    }

    @Test
    void keepsOnlyLatinLinesReturnedUnchanged() {
        assertTrue(TranslationClient.isKeptOriginal("Oh, woo yeah", "Oh, woo yeah"));
        assertTrue(TranslationClient.isKeptOriginal(":-D ;-b hey", " :-D ;-b hey "));
        assertTrue(TranslationClient.isKeptOriginal(":-D ;-b hey", ":-D; ;-b hey"));
        assertFalse(TranslationClient.isKeptOriginal("Oh baby, don't go", "Oh baby, 가지 마"));
        assertFalse(TranslationClient.isKeptOriginal("Mwah!", " "));
        assertFalse(TranslationClient.isKeptOriginal("Oh, woo yeah", "오, 우 예"));
        assertFalse(TranslationClient.isKeptOriginal("消えない", "消えない"));
        assertFalse(TranslationClient.isKeptOriginal("まじでSTOP", "まじでSTOP"));
        assertFalse(TranslationClient.isKeptOriginal("Mwah!", null));
    }

    @Test
    void detectsLatinOnlyLines() {
        assertTrue(LyricsLanguage.isLatinOnly("Mwah!"));
        assertFalse(LyricsLanguage.isLatinOnly("まじでSTOP"));
        assertFalse(LyricsLanguage.isLatinOnly("Oh 사랑해"));
        assertFalse(LyricsLanguage.isLatinOnly("♪ ..."));
    }

    @Test
    void rejectsLeftoverKana() {
        assertFalse(TranslationClient.isAcceptable("消えない", "消えない"));
    }

    @Test
    void rejectsLeftoverCyrillic() {
        assertFalse(TranslationClient.isAcceptable("Я не забуду тебя никогда", "Я не забуду 너를"));
        assertTrue(TranslationClient.isAcceptable("Я не забуду тебя никогда", "난 널 절대 잊지 않을 거야"));
    }

    @Test
    void describesEachLinesLanguageForRetries() {
        assertEquals("Japanese", LyricsLanguage.describe("だけども難しいように"));
        assertEquals("Chinese characters", LyricsLanguage.describe("我爱你"));
        assertEquals("Russian and Latin letters", LyricsLanguage.describe("Я love"));
        assertEquals("Latin letters and Korean", LyricsLanguage.describe("Oh 사랑해"));
        assertEquals("no words", LyricsLanguage.describe("♪ ..."));
    }
}
