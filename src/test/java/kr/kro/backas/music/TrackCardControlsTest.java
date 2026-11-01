package kr.kro.backas.music;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TrackCardControlsTest {

    @Test
    void menuIdCarriesTheBotAndGuild() {
        String id = TrackCardControls.id(TrackCardControls.MENU, "123456789012345678:987654321098765432");
        assertArrayEquals(new String[] {"menu", "123456789012345678", "987654321098765432"}, TrackCardControls.parse(id));
        assertTrue(id.length() <= 100, id);
    }

    @Test
    void buttonsFromOlderCardsStillParse() {
        String id = TrackCardControls.id(TrackCardControls.PAUSE, "1:2");
        assertArrayEquals(new String[] {"pause", "1", "2"}, TrackCardControls.parse(id));
    }

    @Test
    void ignoresOtherComponents() {
        assertNull(TrackCardControls.parse("lyrics-feedback:good:abc"));
        assertNull(TrackCardControls.parse("card:pause"));
    }
}
