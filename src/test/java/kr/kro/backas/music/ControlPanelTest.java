package kr.kro.backas.music;

import kr.kro.backas.music.filter.ConfiguredEqualizer;
import kr.kro.backas.music.filter.KaraokeMode;
import net.dv8tion.jda.api.components.selections.StringSelectMenu;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ControlPanelTest {

    private static final String KEY = "123456789012345678:987654321098765432";

    @Test
    void idsCarryTheActionBotAndGuildWithinDiscordLimits() {
        for (String action : new String[] {ControlPanel.OPEN, ControlPanel.AI_SHUFFLE, ControlPanel.INTERLUDE, ControlPanel.LYRICS_EARLIER}) {
            String id = ControlPanel.id(action, KEY);
            assertTrue(id.length() <= 100, id);
            assertArrayEquals(new String[] {action, "123456789012345678", "987654321098765432"}, ControlPanel.parse(id, ControlPanel.PREFIX));
        }
    }

    @Test
    void ignoresComponentsThatAreNotPanelControls() {
        assertNull(ControlPanel.parse("card:menu:1:2", ControlPanel.PREFIX));
        assertNull(ControlPanel.parse("panel:open", ControlPanel.PREFIX));
        assertNull(ControlPanel.parse(ControlPanel.id(ControlPanel.ADD, KEY), ControlPanel.MODAL_PREFIX));
    }

    @Test
    void effectsMenuFitsEverySpeedEqualizerAndKaraokeMode() {
        StringSelectMenu menu = ControlPanel.effectsMenu(KEY);
        int expected = ControlPanel.SPEEDS.length + ConfiguredEqualizer.values().length + KaraokeMode.values().length;
        assertEquals(expected, menu.getOptions().size());
        assertTrue(expected <= StringSelectMenu.OPTIONS_MAX_AMOUNT, "too many options: " + expected);
    }

    @Test
    void repeatCyclesThroughEveryMode() {
        assertEquals(RepeatMode.REPEAT_ALL, ControlPanel.nextRepeat(RepeatMode.NO_REPEAT));
        assertEquals(RepeatMode.REPEAT_CURRENT, ControlPanel.nextRepeat(RepeatMode.REPEAT_ALL));
        assertEquals(RepeatMode.NO_REPEAT, ControlPanel.nextRepeat(RepeatMode.REPEAT_CURRENT));
    }

    @Test
    void lyricsSyncReadsAsSignedSeconds() {
        assertEquals("0초", ControlPanel.signedSeconds(0));
        assertEquals("+0.5초", ControlPanel.signedSeconds(500));
        assertEquals("−1.5초", ControlPanel.signedSeconds(-1500));
    }

    @Test
    void seekStaysInsideTheSong() {
        assertEquals(0, MusicPlayerClient.clampSeek(-5_000, 200_000));
        assertEquals(199_000, MusicPlayerClient.clampSeek(250_000, 200_000));
        assertEquals(30_000, MusicPlayerClient.clampSeek(30_000, 200_000));
        assertEquals(30_000, MusicPlayerClient.clampSeek(30_000, 0));
    }
}
