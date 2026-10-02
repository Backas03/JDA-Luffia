package kr.kro.backas.music.ai;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AiGuardTest {

    private static final long GUILD = 1L;

    @Test
    void enforcesUserCooldown() {
        AtomicLong now = new AtomicLong(0);
        AiGuard guard = new AiGuard(now::get);
        assertNull(guard.tryAcquire(10L, GUILD, false));
        now.set(AiGuard.USER_COOLDOWN_MS - 1);
        String denied = guard.tryAcquire(10L, GUILD, false);
        assertNotNull(denied);
        assertTrue(denied.contains("초"));
        assertNull(guard.tryAcquire(11L, GUILD, false));
        now.set(AiGuard.USER_COOLDOWN_MS);
        assertNull(guard.tryAcquire(10L, GUILD, false));
    }

    @Test
    void enforcesGuildHourlyLimit() {
        AtomicLong now = new AtomicLong(0);
        AiGuard guard = new AiGuard(now::get);
        for (int i = 0; i < AiGuard.GUILD_HOURLY_LIMIT; i++) {
            assertNull(guard.tryAcquire(100L + i, GUILD, false));
        }
        assertNotNull(guard.tryAcquire(999L, GUILD, false));
        assertNull(guard.tryAcquire(999L, 2L, false));
        now.set(3_600_000L);
        assertNull(guard.tryAcquire(999L, GUILD, false));
    }

    @Test
    void ownerSkipsLimitsButNotKillSwitch() {
        AtomicLong now = new AtomicLong(0);
        AiGuard guard = new AiGuard(now::get);
        assertNull(guard.tryAcquire(1L, GUILD, true));
        assertNull(guard.tryAcquire(1L, GUILD, true));
        guard.setEnabled(false);
        assertNotNull(guard.tryAcquire(1L, GUILD, true));
        assertNotNull(guard.tryAcquire(2L, GUILD, false));
        guard.setEnabled(true);
        assertNull(guard.tryAcquire(2L, GUILD, false));
    }
}
