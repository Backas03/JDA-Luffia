package kr.kro.backas.music.lyrics;

import org.jetbrains.annotations.Nullable;

import java.util.List;

public final class LyricsJumps {

    public enum Jump {
        PREVIOUS_LINE,
        NEXT_LINE,
        INTERLUDE
    }

    public static final long INTERLUDE_LEAD_MS = 2_000;
    static final long INTERLUDE_MIN_MS = 6_000;
    static final long LINE_RESTART_MS = 1_500;
    static final long NEXT_LINE_GUARD_MS = 300;

    private LyricsJumps() {
    }

    @Nullable
    public static Long target(List<LyricLine> lines, long now, Jump jump) {
        return switch (jump) {
            case PREVIOUS_LINE -> previousLine(lines, now);
            case NEXT_LINE -> nextLine(lines, now);
            case INTERLUDE -> interludeEnd(lines, now);
        };
    }

    @Nullable
    private static Long previousLine(List<LyricLine> lines, long now) {
        LyricLine current = null;
        LyricLine before = null;
        for (LyricLine line : lines) {
            if (!sung(line)) continue;
            if (line.timeMs() > now) break;
            before = current;
            current = line;
        }
        if (current == null) return null;
        if (before == null || now - current.timeMs() > LINE_RESTART_MS) return current.timeMs();
        return before.timeMs();
    }

    @Nullable
    private static Long nextLine(List<LyricLine> lines, long now) {
        for (LyricLine line : lines) {
            if (sung(line) && line.timeMs() > now + NEXT_LINE_GUARD_MS) return line.timeMs();
        }
        return null;
    }

    @Nullable
    private static Long interludeEnd(List<LyricLine> lines, long now) {
        LyricLine current = null;
        LyricLine lastSung = null;
        LyricLine next = null;
        for (LyricLine line : lines) {
            if (line.timeMs() <= now) {
                current = line;
                if (sung(line)) lastSung = line;
            } else if (sung(line)) {
                next = line;
                break;
            }
        }
        if (next == null || next.timeMs() - now <= INTERLUDE_LEAD_MS + 1_000) return null;
        boolean quiet = current == null || !sung(current) || now - lastSung.timeMs() >= INTERLUDE_MIN_MS;
        return quiet ? next.timeMs() - INTERLUDE_LEAD_MS : null;
    }

    private static boolean sung(LyricLine line) {
        return line.text() != null && !line.text().isBlank() && !LyricsSession.REST.equals(line.text().strip());
    }
}
