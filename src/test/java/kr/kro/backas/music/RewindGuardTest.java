package kr.kro.backas.music;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RewindGuardTest {
    private final Object track = new Object();

    private static void play(RewindGuard guard, Object track, long from, long to) {
        for (long t = from; t <= to; t += 20) {
            assertTrue(guard.onFrame(track, t).play(), "frame at " + t);
        }
    }

    @Test
    void restartAfterProgressSeeksBackToLastPosition() {
        RewindGuard guard = new RewindGuard();
        guard.start(track, 0, true);
        play(guard, track, 0, 60_000);

        RewindGuard.Verdict verdict = guard.onFrame(track, 0);

        assertFalse(verdict.play());
        assertEquals(60_000, verdict.seekTo());
    }

    @Test
    void framesFromTheStartAreDroppedUntilTheSeekLands() {
        RewindGuard guard = new RewindGuard();
        guard.start(track, 60_000, true);
        guard.onFrame(track, 0);

        RewindGuard.Verdict stale = guard.onFrame(track, 20);

        assertFalse(stale.play());
        assertEquals(-1, stale.seekTo());
        assertTrue(guard.onFrame(track, 60_000).play());
        assertTrue(guard.onFrame(track, 60_020).play());
    }

    @Test
    void resumedTrackRestartingFromZeroSeeksToResumePosition() {
        RewindGuard guard = new RewindGuard();
        guard.start(track, 60_000, true);

        assertEquals(60_000, guard.onFrame(track, 0).seekTo());
        assertEquals(60_000, guard.positionOf(track, 20));
    }

    @Test
    void anotherRestartWhileRecoveringSeeksAgain() {
        RewindGuard guard = new RewindGuard();
        guard.start(track, 60_000, true);
        guard.onFrame(track, 0);
        guard.onFrame(track, 20);
        guard.onFrame(track, 40);

        assertEquals(60_000, guard.onFrame(track, 0).seekTo());
    }

    @Test
    void givesUpAfterTooManyRestarts() {
        RewindGuard guard = new RewindGuard();
        guard.start(track, 60_000, true);
        assertEquals(60_000, guard.onFrame(track, 0).seekTo());
        for (int i = 1; i < RewindGuard.MAX_RESEEKS; i++) {
            guard.onFrame(track, 20);
            assertEquals(60_000, guard.onFrame(track, 0).seekTo());
        }
        guard.onFrame(track, 20);

        assertTrue(guard.onFrame(track, 0).play());
        assertEquals(5, guard.positionOf(track, 5));
    }

    @Test
    void progressAfterRecoveryAllowsLaterRecoveries() {
        RewindGuard guard = new RewindGuard();
        guard.start(track, 60_000, true);
        guard.onFrame(track, 0);
        for (int i = 1; i < RewindGuard.MAX_RESEEKS; i++) {
            guard.onFrame(track, 20);
            guard.onFrame(track, 0);
        }
        play(guard, track, 60_000, 75_000);

        assertEquals(75_000, guard.onFrame(track, 0).seekTo());
    }

    @Test
    void freshTrackFromTheStartIsLeftAlone() {
        RewindGuard guard = new RewindGuard();
        guard.start(track, 0, true);

        play(guard, track, 0, 5_000);
    }

    @Test
    void seekToTheStartIsNotTreatedAsRestart() {
        RewindGuard guard = new RewindGuard();
        guard.start(track, 0, true);
        play(guard, track, 0, 100_000);
        guard.seeked(track, 0);

        play(guard, track, 100_020, 101_000);
        play(guard, track, 0, 2_000);
    }

    @Test
    void ghostFramesAfterForwardSeekAreNotTreatedAsRestart() {
        RewindGuard guard = new RewindGuard();
        guard.start(track, 0, true);
        play(guard, track, 0, 1_000);
        guard.seeked(track, 120_000);

        play(guard, track, 1_020, 2_000);
        play(guard, track, 120_000, 121_000);
    }

    @Test
    void framesOfAnotherTrackAreIgnored() {
        RewindGuard guard = new RewindGuard();
        guard.start(track, 60_000, true);

        assertTrue(guard.onFrame(new Object(), 0).play());
        assertEquals(7, guard.positionOf(new Object(), 7));
    }

    @Test
    void unseekableTrackIsNotGuarded() {
        RewindGuard guard = new RewindGuard();
        guard.start(track, 60_000, false);

        assertTrue(guard.onFrame(track, 0).play());
    }

    @Test
    void recoveryThatNeverLandsStopsDroppingAfterAWhile() {
        RewindGuard guard = new RewindGuard();
        guard.start(track, 60_000, true);
        guard.onFrame(track, 0);
        long t = 20;
        for (int i = 0; i < RewindGuard.WAIT_FRAMES; i++, t += 20) {
            assertFalse(guard.onFrame(track, t).play());
        }

        assertTrue(guard.onFrame(track, t).play());
    }
}
