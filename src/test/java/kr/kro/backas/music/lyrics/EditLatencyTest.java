package kr.kro.backas.music.lyrics;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EditLatencyTest {

    private static final long CHANNEL = 42L;

    @BeforeEach
    void reset() {
        EditLatency.reset();
    }

    @Test
    void startsFromTheOldFixedLeadUntilSamplesArrive() {
        assertEquals(EditLatency.INITIAL_LEAD_MS, EditLatency.leadMs(CHANNEL));
    }

    @Test
    void movesTowardsTheMeasuredLatencyAndStaysWithinBounds() {
        for (int i = 0; i < 20; i++) EditLatency.record(CHANNEL, 900);
        long lead = EditLatency.leadMs(CHANNEL);
        assertTrue(lead >= 850 && lead <= 950, String.valueOf(lead));

        for (int i = 0; i < 30; i++) EditLatency.record(CHANNEL, 100);
        assertEquals(EditLatency.MIN_LEAD_MS, EditLatency.leadMs(CHANNEL));

        for (int i = 0; i < 30; i++) EditLatency.record(CHANNEL, 4000);
        assertEquals(EditLatency.MAX_LEAD_MS, EditLatency.leadMs(CHANNEL));
    }

    @Test
    void ignoresImpossibleSamplesAndKeepsChannelsApart() {
        EditLatency.record(CHANNEL, 20_000);
        EditLatency.record(CHANNEL, -5);
        assertEquals(EditLatency.INITIAL_LEAD_MS, EditLatency.leadMs(CHANNEL));

        for (int i = 0; i < 20; i++) EditLatency.record(7L, 1200);
        assertEquals(EditLatency.INITIAL_LEAD_MS, EditLatency.leadMs(CHANNEL));
        assertTrue(EditLatency.leadMs(7L) > 1100);
    }
}
