package kr.kro.backas.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DiscordSafeTest {

    @Test
    void neutralizesMassMentions() {
        String safe = DiscordSafe.text("hey @everyone and @here", 100);
        assertFalse(safe.contains("@everyone"));
        assertFalse(safe.contains("@here"));
        assertTrue(safe.contains("＠everyone"));
    }

    @Test
    void removesUserRoleChannelAndCommandMentions() {
        assertEquals("a  b  c  d ", DiscordSafe.text("a <@123> b <@&456> c <#789> d </ban:42>", 100));
        assertEquals("x ", DiscordSafe.text("x <@!123>", 100));
    }

    @Test
    void replacesLinksAndInvites() {
        assertEquals("go " + DiscordSafe.LINK_REMOVED, DiscordSafe.text("go https://evil.example/free", 100));
        assertEquals("join " + DiscordSafe.LINK_REMOVED, DiscordSafe.text("join discord.gg/abc123", 100));
        assertEquals("see " + DiscordSafe.LINK_REMOVED, DiscordSafe.text("see www.evil.example", 100));
    }

    @Test
    void unwrapsMarkdownLinks() {
        assertEquals("무료 니트로", DiscordSafe.text("[무료 니트로](https://evil.example)", 100));
    }

    @Test
    void stripsInvisibleCharacters() {
        assertEquals("abc", DiscordSafe.text("a​b‮c", 100));
        assertEquals("line1\nline2", DiscordSafe.text("line1\nline2\u0007", 100));
    }

    @Test
    void truncatesWithoutSplittingSurrogates() {
        String safe = DiscordSafe.text("ab😀cd", 4);
        assertTrue(safe.endsWith("…"));
        assertFalse(Character.isHighSurrogate(safe.charAt(safe.length() - 2)));
        assertEquals("짧은글", DiscordSafe.text("짧은글", 10));
    }

    @Test
    void escapesMarkdownForEcho() {
        assertEquals("\\*\\*굵게\\*\\*", DiscordSafe.escaped("**굵게**", 100));
    }

    @Test
    void detectsLinksAndMentions() {
        assertTrue(DiscordSafe.hasLinkOrMention("https://x.example"));
        assertTrue(DiscordSafe.hasLinkOrMention("@everyone"));
        assertTrue(DiscordSafe.hasLinkOrMention("<@123>"));
        assertTrue(DiscordSafe.hasLinkOrMention("discord.gg/abc"));
        assertFalse(DiscordSafe.hasLinkOrMention("일본 보카로곡"));
        assertFalse(DiscordSafe.hasLinkOrMention(null));
    }
}
