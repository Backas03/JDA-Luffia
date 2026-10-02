package kr.kro.backas.music.lyrics;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EditRateLimiterTest {

    private static final AtomicLong CHANNELS = new AtomicLong(9_000_000L);
    private static final long[] NONE = new long[0];
    private static final long TICK_MS = 200;
    private static final long CLOCK_EDIT_INTERVAL_MS = 800;
    private static final long POST_EDIT_GAP_MS = 500;

    private record Outcome(List<Long> lineDelays, int clockEdits, int skippedSeconds) {
    }

    private static Outcome simulate(long[] lineTimes, long durationMs) {
        long channel = CHANNELS.incrementAndGet();
        long start = 1_000_000L;
        List<Long> lineDelays = new ArrayList<>();
        int shownLine = -1;
        long shownSecond = -1;
        long lastEditAt = 0;
        int clockEdits = 0;
        int skipped = 0;
        for (long elapsed = 0; elapsed <= durationMs; elapsed += TICK_MS) {
            long now = start + elapsed;
            int line = -1;
            for (int i = 0; i < lineTimes.length; i++) {
                if (lineTimes[i] <= elapsed) line = i;
            }
            long second = elapsed / 1000;
            if (line != shownLine) {
                if (now - lastEditAt < POST_EDIT_GAP_MS) continue;
                if (!EditRateLimiter.tryAcquire(channel, now, NONE)) continue;
                lineDelays.add(elapsed - lineTimes[line]);
                shownLine = line;
            } else {
                if (second == shownSecond || now - lastEditAt < CLOCK_EDIT_INTERVAL_MS) continue;
                long nextLineIn = line + 1 < lineTimes.length ? lineTimes[line + 1] - elapsed : Long.MAX_VALUE;
                if (nextLineIn <= POST_EDIT_GAP_MS + TICK_MS) continue;
                List<Long> upcoming = new ArrayList<>();
                for (int i = line + 1; i < lineTimes.length && upcoming.size() < EditRateLimiter.MAX_EDITS_PER_WINDOW; i++) {
                    if (lineTimes[i] - elapsed > EditRateLimiter.WINDOW_MS) break;
                    upcoming.add(start + lineTimes[i]);
                }
                long[] upcomingAt = upcoming.stream().mapToLong(Long::longValue).toArray();
                if (!EditRateLimiter.tryAcquire(channel, now, upcomingAt)) continue;
                clockEdits++;
            }
            if (shownSecond >= 0 && second > shownSecond + 1) skipped += (int) (second - shownSecond - 1);
            shownSecond = second;
            lastEditAt = now;
        }
        return new Outcome(lineDelays, clockEdits, skipped);
    }

    private static long[] every(long firstMs, long gapMs, long untilMs) {
        List<Long> times = new ArrayList<>();
        for (long at = firstMs; at < untilMs; at += gapMs) times.add(at);
        return times.stream().mapToLong(Long::longValue).toArray();
    }

    @Test
    void allowsFiveEditsPerWindowAndNoMore() {
        long channel = CHANNELS.incrementAndGet();
        long now = 5_000_000L;
        for (int i = 0; i < EditRateLimiter.MAX_EDITS_PER_WINDOW; i++) {
            assertTrue(EditRateLimiter.tryAcquire(channel, now + i * 100L, NONE));
        }
        assertFalse(EditRateLimiter.tryAcquire(channel, now + 600, NONE));
        assertTrue(EditRateLimiter.tryAcquire(channel, now + EditRateLimiter.WINDOW_MS, NONE));
    }

    @Test
    void keepsRoomForAnUpcomingEdit() {
        long channel = CHANNELS.incrementAndGet();
        long now = 6_000_000L;
        for (int i = 0; i < 4; i++) assertTrue(EditRateLimiter.tryAcquire(channel, now + i * 100L, NONE));
        assertFalse(EditRateLimiter.tryAcquire(channel, now + 500, new long[]{now + 1500}));
        assertTrue(EditRateLimiter.tryAcquire(channel, now + 500, new long[]{now + 1500 + EditRateLimiter.WINDOW_MS}));
    }

    @Test
    void clockClimbsEverySecondWhenNoLyricsAreNear() {
        Outcome outcome = simulate(new long[0], 60_000);
        assertTrue(outcome.clockEdits() >= 57, "clock edits: " + outcome.clockEdits());
        assertTrue(outcome.skippedSeconds() <= 2, "skipped seconds: " + outcome.skippedSeconds());
    }

    @Test
    void lyricLinesStayOnTimeWhileTheClockUsesTheSpareBudget() {
        Outcome outcome = simulate(every(3_000, 4_000, 60_000), 60_000);
        assertEquals(15, outcome.lineDelays().size());
        for (long delay : outcome.lineDelays()) assertTrue(delay <= TICK_MS, "line shown " + delay + "ms late");
        assertTrue(outcome.clockEdits() >= 36, "clock edits: " + outcome.clockEdits());
        assertTrue(outcome.skippedSeconds() <= 8, "skipped seconds: " + outcome.skippedSeconds());
    }

    @Test
    void denseLyricsTakeTheBudgetBeforeTheClock() {
        Outcome outcome = simulate(every(1_000, 1_300, 30_000), 30_000);
        assertEquals(23, outcome.lineDelays().size());
        for (long delay : outcome.lineDelays()) assertTrue(delay <= TICK_MS, "line shown " + delay + "ms late");
    }
}
