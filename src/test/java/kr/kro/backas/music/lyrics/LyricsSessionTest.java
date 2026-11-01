package kr.kro.backas.music.lyrics;

import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.components.container.ContainerChildComponent;
import net.dv8tion.jda.api.components.section.Section;
import net.dv8tion.jda.api.components.textdisplay.TextDisplay;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LyricsSessionTest {

    @Test
    void gapButtonSpansTheBlankLineAndTheClockWithoutAddingLines() {
        Lyrics lyrics = new Lyrics("t", "a", null,
                List.of(new LyricLine(0, "first"), new LyricLine(1000, "second"), new LyricLine(2000, "third")), false);
        LyricsSession.Body parts = LyricsSession.parts(null, null, lyrics, 1, null, null, "1:38 — 4:22", null, false, false, null);

        List<ContainerChildComponent> plain = LyricsSession.cardComponents(parts, null);
        List<ContainerChildComponent> withJump = LyricsSession.cardComponents(parts, Button.primary("card:gap:1:2", "간주 점프"));

        assertEquals(2, plain.size());
        assertEquals(2, withJump.size());
        String block = ((TextDisplay) plain.get(1)).getContent();
        Section section = assertInstanceOf(Section.class, withJump.get(1));
        assertEquals(block, ((TextDisplay) section.getContentComponents().get(0)).getContent());
        assertEquals("-# " + LyricsSession.SLOT_BLANK + "\n-# 1:38 — 4:22", block);
        assertTrue(((TextDisplay) plain.get(0)).getContent().endsWith("*third*\n-# " + LyricsSession.CARD_FILLER));
    }

    @Test
    void untranslatedLineKeepsAnEmptyRowWhereTheTranslationWouldGo() {
        Lyrics lyrics = new Lyrics("t", "a", null,
                List.of(new LyricLine(0, "first"), new LyricLine(1000, "second"), new LyricLine(2000, "third")), false);
        String text = LyricsSession.body(null, null, lyrics, 1, null, null, "footer", null, false, false, null).getContent();
        assertTrue(text.startsWith("*first*\n## second\n​\n*third*\n"), text);
    }

    @Test
    void pronunciationSitsInItalicsBetweenTheLineAndItsTranslation() {
        Lyrics lyrics = new Lyrics("t", "a", null,
                List.of(new LyricLine(0, "君の声"), new LyricLine(1000, "あー恋の"), new LyricLine(2000, "夜の街")), false);
        String text = LyricsSession.body(null, null, lyrics, 1, "아, 사랑의", "아아 코이노", "footer", null, false, false, null)
                .getContent();
        assertTrue(text.startsWith("*君の声*\n## あー恋の\n-# *아아 코이노*\n-# 아, 사랑의\n​\n*夜の街*\n"), text);
    }

    @Test
    void restLineKeepsThePronunciationRowAsADash() {
        Lyrics lyrics = new Lyrics("t", "a", null,
                List.of(new LyricLine(0, "君の声"), new LyricLine(1000, ""), new LyricLine(2000, "夜の街")), false);
        String text = LyricsSession.body(null, null, lyrics, 1, LyricsSession.REST, LyricsSession.NO_PRONUNCIATION,
                "footer", null, false, false, null).getContent();
        assertTrue(text.startsWith("*君の声*\n## ♪\n-# -\n-# ♪\n"), text);
    }

    @Test
    void pronunciationStillBeingBuiltShowsAPlainNote() {
        Lyrics lyrics = new Lyrics("t", "a", null,
                List.of(new LyricLine(0, "君の声"), new LyricLine(1000, "ごまかして笑っていくよ"), new LyricLine(2000, "夜の街")), false);
        String text = LyricsSession.body(null, null, lyrics, 1, "속이며 웃어 나갈게", LyricsSession.PRONOUNCING_NOTE,
                "footer", null, false, false, null).getContent();
        assertTrue(text.startsWith("*君の声*\n## ごまかして笑っていくよ\n-# 해석 중\n-# 속이며 웃어 나갈게\n"), text);
    }
}
