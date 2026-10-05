package kr.kro.backas.music;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PositionSyncTest {

    @Test
    void smallDriftIsLeftAlone() {
        assertEquals(0, PositionSync.correction(10_200, 10_000, 1.0, 0, 0));
        assertEquals(0, PositionSync.correction(9_800, 10_000, 1.0, 0, 0));
    }

    @Test
    void driftBeyondToleranceIsCorrectedInEitherDirection() {
        assertEquals(1_000, PositionSync.correction(9_000, 10_000, 1.0, 0, 0));
        assertEquals(-1_000, PositionSync.correction(11_000, 10_000, 1.0, 0, 0));
    }

    @Test
    void comparisonIsRelativeToTheLastCheckpoint() {
        assertEquals(0, PositionSync.correction(65_000, 50_000, 1.0, 40_000, 55_000));
        assertEquals(-2_000, PositionSync.correction(67_000, 50_000, 1.0, 40_000, 55_000));
    }

    @Test
    void speedScalesTheExpectedAdvanceBecauseTrackPositionCountsOutputTime() {
        assertEquals(0, PositionSync.correction(15_000, 10_000, 1.5, 0, 0));
        assertEquals(5_000, PositionSync.correction(10_000, 10_000, 1.5, 0, 0));
        assertEquals(0, PositionSync.correction(5_000, 10_000, 0.5, 0, 0));
    }
}
