package kr.kro.backas.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DurationUtilTest {

    @Test
    void formatsClockWithoutLeadingZeroOnFirstUnit() {
        assertEquals("0:00", DurationUtil.formatClock(0));
        assertEquals("0:12", DurationUtil.formatClock(12));
        assertEquals("3:33", DurationUtil.formatClock(213));
        assertEquals("10:05", DurationUtil.formatClock(605));
    }

    @Test
    void formatsClockWithHours() {
        assertEquals("1:00:00", DurationUtil.formatClock(3600));
        assertEquals("1:02:03", DurationUtil.formatClock(3723));
    }
}
