package kr.kro.backas.music.lyrics;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class EditRateLimiter {

    public static final int MAX_EDITS_PER_WINDOW = 4;
    public static final long WINDOW_MS = 5000;

    private static final Map<Long, Deque<Long>> HISTORY = new ConcurrentHashMap<>();

    private EditRateLimiter() {
    }

    public static boolean tryAcquire(long channelId) {
        Deque<Long> stamps = HISTORY.computeIfAbsent(channelId, id -> new ArrayDeque<>());
        synchronized (stamps) {
            long now = System.currentTimeMillis();
            while (!stamps.isEmpty() && now - stamps.peekFirst() >= WINDOW_MS) stamps.pollFirst();
            if (stamps.size() >= MAX_EDITS_PER_WINDOW) return false;
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
