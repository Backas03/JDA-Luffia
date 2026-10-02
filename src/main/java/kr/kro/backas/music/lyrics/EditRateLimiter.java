package kr.kro.backas.music.lyrics;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class EditRateLimiter {

    public static final int MAX_EDITS_PER_WINDOW = 5;
    public static final long WINDOW_MS = 5125;

    private static final Map<Long, Deque<Long>> HISTORY = new ConcurrentHashMap<>();

    private EditRateLimiter() {
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
