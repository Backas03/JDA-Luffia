package kr.kro.backas.music.lyrics;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LyricsPresenterTest {

    @Test
    void fullBodyInterleavesTranslationsAndEndsWithTheFooter() {
        String body = LyricsPresenter.fullBody(List.of("夜に駆ける", "さよなら"), Map.of(0, "밤을 달리다"), "0:12 — 3:33 · 전체 가사", null, 4000);
        assertEquals("夜に駆ける\n-# 밤을 달리다\nさよなら\n\n-# 0:12 — 3:33 · 전체 가사", body);
    }

    @Test
    void previewHintSitsBetweenTheLinesAndTheFooter() {
        String body = LyricsPresenter.fullBody(List.of("a", "b"), null, "footer", null, 4000, "\n-# 외 12줄");
        assertEquals("a\nb\n\n-# 외 12줄\n-# footer", body);
    }

    @Test
    void fullBodyStaysInsideTheBudgetAndSaysWhatWasCut() {
        List<String> lines = new ArrayList<>();
        for (int i = 0; i < 200; i++) lines.add("line " + i + " " + "가".repeat(40));
        String body = LyricsPresenter.fullBody(lines, null, "footer", "note 1\nnote 2", LyricsPresenter.CARD_TEXT_BUDGET);
        assertTrue(body.length() <= LyricsPresenter.CARD_TEXT_BUDGET, "length " + body.length());
        assertTrue(body.contains("… (이하 생략)"));
        assertTrue(body.endsWith("-# footer\n-# note 1\n-# note 2"));
        assertFalse(body.contains("line 199"));
    }
}
