package kr.kro.backas.music.lyrics.sync;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class OffsetPolicyTest {

    @Test
    void refinedOffsetsAreTrustedDownToTwoHundredMilliseconds() {
        assertEquals(0, OffsetPolicy.resolve(-450, new OnsetRefiner.Refinement(-150, 4.0, 6)));
        assertEquals(-250, OffsetPolicy.resolve(-450, new OnsetRefiner.Refinement(-250, 4.0, 6)));
        assertEquals(-13_700, OffsetPolicy.resolve(-13_300, new OnsetRefiner.Refinement(-13_700, 3.1, 7)));
    }

    @Test
    void whisperOnlyOffsetsNeedToClearItsOwnErrorBand() {
        assertEquals(0, OffsetPolicy.resolve(-450, null));
        assertEquals(0, OffsetPolicy.resolve(790, null));
        assertEquals(-900, OffsetPolicy.resolve(-900, null));
        assertEquals(-13_300, OffsetPolicy.resolve(-13_300, null));
    }
}
