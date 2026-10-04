package kr.kro.backas.music.lyrics;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeoutException;

public final class EditRateLimiter {

    public static final int MAX_EDITS_PER_WINDOW = 5;
    public static final long WINDOW_MS = 5000;
    public static final long EDIT_DEADLINE_MS = 2500;
    public static final long IN_FLIGHT_TIMEOUT_MS = 4000;
    static final long EXTRAS_PAUSE_MS = 30_000;

    private static final Logger LOGGER = LoggerFactory.getLogger(EditRateLimiter.class);
    private static final Map<Long, Deque<Long>> HISTORY = new ConcurrentHashMap<>();
    private static final Map<Long, Long> EXTRAS_PAUSED_UNTIL = new ConcurrentHashMap<>();

    private EditRateLimiter() {
    }

    public static boolean extrasAllowed(long channelId) {
        return extrasAllowed(channelId, System.currentTimeMillis());
    }

    static boolean extrasAllowed(long channelId, long now) {
        Long until = EXTRAS_PAUSED_UNTIL.get(channelId);
        return until == null || now >= until;
    }

    public static void reportHeldBack(long channelId, String what) {
        reportHeldBack(channelId, what, System.currentTimeMillis());
    }

    static void reportHeldBack(long channelId, String what, long now) {
        boolean alreadyPaused = !extrasAllowed(channelId, now);
        EXTRAS_PAUSED_UNTIL.put(channelId, now + EXTRAS_PAUSE_MS);
        if (!alreadyPaused) {
            LOGGER.warn("lyrics message edits in channel {} are being held back ({}); pausing time-only updates for {}s",
                    channelId, what, EXTRAS_PAUSE_MS / 1000);
        }
    }

    public static boolean isHeldBack(Throwable error) {
        for (Throwable current = error; current != null; current = current.getCause()) {
            if (current instanceof TimeoutException || current instanceof CancellationException
                    || current.getClass().getSimpleName().equals("RateLimitedException")) {
                return true;
            }
        }
        return false;
    }

    private static final long[] NOTHING_UPCOMING = new long[0];

    public static boolean tryAcquire(long channelId) {
        return tryAcquire(channelId, NOTHING_UPCOMING);
    }

    public static boolean tryAcquire(long channelId, long[] upcomingEditsAt) {
        return tryAcquire(channelId, System.currentTimeMillis(), upcomingEditsAt);
    }

    static boolean tryAcquire(long channelId, long now, long[] upcomingEditsAt) {
        Deque<Long> stamps = HISTORY.computeIfAbsent(channelId, id -> new ArrayDeque<>());
        synchronized (stamps) {
            while (!stamps.isEmpty() && now - stamps.peekFirst() >= WINDOW_MS) stamps.pollFirst();
            if (stamps.size() >= MAX_EDITS_PER_WINDOW) return false;
            for (int k = 0; k < upcomingEditsAt.length; k++) {
                long windowStart = upcomingEditsAt[k] - WINDOW_MS;
                int used = now > windowStart ? 1 : 0;
                for (long stamp : stamps) {
                    if (stamp > windowStart) used++;
                }
                for (int i = 0; i < k; i++) {
                    if (upcomingEditsAt[i] > windowStart) used++;
                }
                if (used >= MAX_EDITS_PER_WINDOW) return false;
            }
            stamps.addLast(now);
            return true;
        }
    }

    public static long millisUntilNext(long channelId) {
        Deque<Long> stamps = HISTORY.get(channelId);
        if (stamps == null) return 0;
        synchronized (stamps) {
            if (stamps.size() < MAX_EDITS_PER_WINDOW) return 0;
            return Math.max(0, stamps.peekFirst() + WINDOW_MS - System.currentTimeMillis());
        }
    }
}
