package kr.kro.backas.music.lyrics;

import kr.kro.backas.util.DiscordSafe;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

public final class TranslationText {

    static final char SEPARATOR = '\u001F';

    private TranslationText() {
    }

    public static String combine(@Nullable String reading, @Nullable String translation) {
        String text = translation == null ? "" : translation;
        String spoken = cleanReading(reading);
        return spoken.isEmpty() ? text : spoken + SEPARATOR + text;
    }

    public static String translation(@Nullable String value) {
        if (value == null) return "";
        int at = value.indexOf(SEPARATOR);
        return at < 0 ? value : value.substring(at + 1);
    }

    @Nullable
    public static String reading(@Nullable String value) {
        if (value == null) return null;
        int at = value.indexOf(SEPARATOR);
        return at < 0 ? null : value.substring(0, at);
    }

    public static List<String> displayLines(@Nullable String value, int maxLength) {
        List<String> lines = new ArrayList<>(2);
        String spoken = reading(value);
        if (spoken != null && !spoken.isBlank()) lines.add("*(" + DiscordSafe.text(spoken, maxLength) + ")*");
        String text = translation(value);
        if (!text.isBlank()) lines.add(DiscordSafe.text(text, maxLength));
        return lines;
    }

    public static String markdown(@Nullable String value, int maxLength) {
        StringBuilder out = new StringBuilder();
        for (String line : displayLines(value, maxLength)) {
            if (!out.isEmpty()) out.append('\n');
            out.append("-# ").append(line);
        }
        return out.toString();
    }

    static String cleanReading(@Nullable String reading) {
        if (reading == null) return "";
        String cleaned = reading.strip();
        while (cleaned.length() >= 2 && (cleaned.startsWith("(") || cleaned.startsWith("（"))
                && (cleaned.endsWith(")") || cleaned.endsWith("）"))) {
            cleaned = cleaned.substring(1, cleaned.length() - 1).strip();
        }
        cleaned = cleaned.replace(String.valueOf(SEPARATOR), "").replaceAll("\\s+", " ");
        boolean hangul = false;
        for (int i = 0; i < cleaned.length() && !hangul; i++) {
            char c = cleaned.charAt(i);
            hangul = c >= 0xAC00 && c <= 0xD7A3;
        }
        return hangul ? cleaned : "";
    }
}
