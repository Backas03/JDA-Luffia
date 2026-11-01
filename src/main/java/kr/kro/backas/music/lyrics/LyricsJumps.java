package kr.kro.backas.music.lyrics;

import org.jetbrains.annotations.Nullable;

import java.util.List;

public final class LyricsJumps {

    public enum Jump {
        PREVIOUS_LINE,
        NEXT_LINE,
        INTERLUDE
    }

    public enum ButtonState {
        HIDDEN,
        ENABLED,
        DISABLED
    }

    public static final long INTERLUDE_LEAD_MS = 2_000;
    public static final long MIN_GAP_MS = 5_000;
    public static final long DISABLE_BEFORE_MS = 2_500;
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
    public static Long quietSince(List<LyricLine> lines, long now) {
        LyricLine current = null;
        LyricLine lastSung = null;
        boolean anySung = false;
        for (LyricLine line : lines) {
            if (sung(line)) anySung = true;
            if (line.timeMs() <= now) {
                current = line;
                if (sung(line)) lastSung = line;
            }
        }
        if (!anySung) return null;
        if (lastSung == null) return 0L;
        if (!sung(current)) return current.timeMs();
        long since = lastSung.timeMs() + INTERLUDE_MIN_MS;
        return now >= since ? since : null;
    }

    @Nullable
    public static Long nextSung(List<LyricLine> lines, long now) {
        for (LyricLine line : lines) {
            if (sung(line) && line.timeMs() > now) return line.timeMs();
        }
        return null;
    }

    public static boolean inOutro(List<LyricLine> lines, long now) {
        return quietSince(lines, now) != null && nextSung(lines, now) == null;
    }

    public static ButtonState buttonState(long quietSince, long end, long now) {
        if (end - quietSince < MIN_GAP_MS) return ButtonState.HIDDEN;
        long remaining = end - now;
        if (remaining <= 0) return ButtonState.HIDDEN;
        return remaining > DISABLE_BEFORE_MS ? ButtonState.ENABLED : ButtonState.DISABLED;
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
        Long next = nextSung(lines, now);
        if (next == null || quietSince(lines, now) == null || next - now <= DISABLE_BEFORE_MS) return null;
        return next - INTERLUDE_LEAD_MS;
    }

    private static boolean sung(LyricLine line) {
        return line.text() != null && !line.text().isBlank() && !LyricsSession.REST.equals(line.text().strip());
    }
}
