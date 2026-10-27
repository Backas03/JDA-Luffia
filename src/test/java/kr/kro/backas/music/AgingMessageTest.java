package kr.kro.backas.music;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgingMessageTest {

    @Test
    void repostsBeforeDiscordLocksEditsOnHourOldMessages() {
        long createdAt = 1_000_000;
        assertFalse(AgingMessage.isAging(createdAt, createdAt + AgingMessage.REPOST_AGE_MS - 1));
        assertTrue(AgingMessage.isAging(createdAt, createdAt + AgingMessage.REPOST_AGE_MS));
        assertTrue(AgingMessage.REPOST_AGE_MS < 60L * 60 * 1000);
    }
}
