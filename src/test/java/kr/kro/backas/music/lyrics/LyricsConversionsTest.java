package kr.kro.backas.music.lyrics;

import net.dv8tion.jda.api.components.buttons.Button;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LyricsConversionsTest {

    @Test
    void redoButtonSitsBesideTheSwitchButtonWithItsOwnId() {
        Button convert = LyricsConversions.button("t", false, true);
        Button redo = LyricsConversions.redoButton("t");
        assertEquals(LyricsConversions.LABEL_SWITCH, convert.getLabel());
        assertEquals(LyricsConversions.LABEL_REDO, redo.getLabel());
        assertFalse(redo.getCustomId().startsWith("lyrics-ai:"));
        assertTrue(redo.getCustomId().endsWith("t"));
        assertFalse(redo.isDisabled());
    }
}
