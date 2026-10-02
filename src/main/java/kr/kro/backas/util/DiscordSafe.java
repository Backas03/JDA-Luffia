package kr.kro.backas.util;

import net.dv8tion.jda.api.utils.MarkdownSanitizer;
import org.jetbrains.annotations.Nullable;

import java.util.regex.Pattern;

public final class DiscordSafe {

    public static final String LINK_REMOVED = "[링크 제거됨]";

    private static final Pattern INVISIBLE = Pattern.compile("[\\p{Cntrl}&&[^\\n\\t]]|[\\u200B-\\u200F\\u202A-\\u202E\\u2060-\\u2069\\uFEFF]");
    private static final Pattern MARKDOWN_LINK = Pattern.compile("\\[([^\\]]*)\\]\\(\\s*<?[^)\\s]*>?\\s*\\)");
    private static final Pattern ENTITY_MENTION = Pattern.compile("<(?:@[!&]?|#)\\d+>|</[^<>\\s]+:\\d+>");
    private static final Pattern MASS_MENTION = Pattern.compile("(?i)@(everyone|here)");
    private static final Pattern INVITE = Pattern.compile("(?i)\\b(?:discord(?:app)?\\.(?:gg|com/invite|io|me|li)|dsc\\.gg)/\\S*");
    private static final Pattern URL = Pattern.compile("(?i)\\b(?:https?://|www\\.)\\S+");

    private DiscordSafe() {
    }

    public static String text(@Nullable String value, int maxLength) {
        if (value == null || value.isEmpty()) return "";
        String cleaned = INVISIBLE.matcher(value).replaceAll("");
        cleaned = MARKDOWN_LINK.matcher(cleaned).replaceAll("$1");
        cleaned = ENTITY_MENTION.matcher(cleaned).replaceAll("");
        cleaned = INVITE.matcher(cleaned).replaceAll(LINK_REMOVED);
        cleaned = URL.matcher(cleaned).replaceAll(LINK_REMOVED);
        cleaned = MASS_MENTION.matcher(cleaned).replaceAll("＠$1");
        return truncate(cleaned, maxLength);
    }

    public static String escaped(@Nullable String value, int maxLength) {
        return MarkdownSanitizer.escape(text(value, maxLength));
    }

    public static boolean hasLinkOrMention(@Nullable String value) {
        if (value == null || value.isEmpty()) return false;
        return MARKDOWN_LINK.matcher(value).find()
                || ENTITY_MENTION.matcher(value).find()
                || MASS_MENTION.matcher(value).find()
                || INVITE.matcher(value).find()
                || URL.matcher(value).find();
    }

    private static String truncate(String value, int maxLength) {
        if (maxLength <= 0 || value.length() <= maxLength) return value;
        int end = maxLength - 1;
        if (end > 0 && Character.isHighSurrogate(value.charAt(end - 1))) end--;
        return value.substring(0, end) + "…";
    }
}
