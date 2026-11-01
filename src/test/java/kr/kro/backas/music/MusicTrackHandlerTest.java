package kr.kro.backas.music;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MusicTrackHandlerTest {

    @Test
    void streamCutAfterAMinuteIsResumed() {
        assertTrue(MusicTrackHandler.shouldResume(true, false, 221_000, 0, 60_000));
    }

    @Test
    void resumedTrackThatPlayedAgainIsResumedAgain() {
        assertTrue(MusicTrackHandler.shouldResume(true, false, 221_000, 60_000, 120_000));
    }

    @Test
    void attemptWithoutProgressIsNotRetried() {
        assertFalse(MusicTrackHandler.shouldResume(true, false, 221_000, 60_000, 62_000));
        assertFalse(MusicTrackHandler.shouldResume(true, false, 221_000, 0, 3_000));
    }

    @Test
    void failureRightBeforeTheEndIsLeftAsFinished() {
        assertFalse(MusicTrackHandler.shouldResume(true, false, 221_000, 0, 218_000));
    }

    @Test
    void liveStreamsAndUnseekableTracksAreNotResumed() {
        assertFalse(MusicTrackHandler.shouldResume(true, true, Long.MAX_VALUE, 0, 60_000));
        assertFalse(MusicTrackHandler.shouldResume(false, false, 221_000, 0, 60_000));
    }

    @Test
    void unknownLengthIsResumed() {
        assertTrue(MusicTrackHandler.shouldResume(true, false, 0, 0, 60_000));
    }
}
