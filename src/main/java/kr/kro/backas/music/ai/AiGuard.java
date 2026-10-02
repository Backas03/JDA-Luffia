package kr.kro.backas.music.ai;

import org.jetbrains.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import java.util.function.LongSupplier;

public class AiGuard {

    public static final long USER_COOLDOWN_MS = 20_000;
    public static final int GUILD_HOURLY_LIMIT = 60;
    public static final int MAX_QUEUE = 200;
    private static final long HOUR_MS = 3_600_000;

    private final LongSupplier clock;
    private final Map<Long, Long> lastUse = new HashMap<>();
    private final Map<Long, Deque<Long>> guildUses = new HashMap<>();
    private volatile boolean enabled = true;

    public AiGuard() {
        this(System::currentTimeMillis);
    }

    AiGuard(LongSupplier clock) {
        this.clock = clock;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    @Nullable
    public synchronized String tryAcquire(long userId, long guildId, boolean owner) {
        if (!enabled) return "AI 기능이 관리자에 의해 잠시 꺼져 있습니다.";
        if (owner) return null;
        long now = clock.getAsLong();
        Long last = lastUse.get(userId);
        if (last != null && now - last < USER_COOLDOWN_MS) {
            long seconds = (USER_COOLDOWN_MS - (now - last) + 999) / 1000;
            return "AI 기능은 " + seconds + "초 뒤에 다시 사용할 수 있습니다.";
        }
        Deque<Long> uses = guildUses.computeIfAbsent(guildId, id -> new ArrayDeque<>());
        while (!uses.isEmpty() && now - uses.peekFirst() >= HOUR_MS) uses.pollFirst();
        if (uses.size() >= GUILD_HOURLY_LIMIT) {
            long minutes = (HOUR_MS - (now - uses.peekFirst()) + 59_999) / 60_000;
            return "이 서버의 AI 사용량이 한도(시간당 " + GUILD_HOURLY_LIMIT + "회)에 도달했습니다. " + minutes + "분 뒤에 다시 시도해주세요.";
        }
        lastUse.put(userId, now);
        uses.addLast(now);
        return null;
    }
}
