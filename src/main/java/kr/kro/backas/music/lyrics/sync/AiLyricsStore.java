package kr.kro.backas.music.lyrics.sync;

import kr.kro.backas.music.cache.DiskCache;
import kr.kro.backas.music.lyrics.LyricLine;
import kr.kro.backas.music.lyrics.Lyrics;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

public final class AiLyricsStore {

    public static final int VERSION = 2;

    public record Line(long timeMs, String text) {
    }

    public record Stored(List<Line> lines, int matched, int total, long at, int version) {
    }

    private static final AiLyricsStore DEFAULT = new AiLyricsStore(DiskCache.defaultCache());

    private final DiskCache cache;

    public AiLyricsStore(DiskCache cache) {
        this.cache = cache;
    }

    public static AiLyricsStore defaultStore() {
        return DEFAULT;
    }

    @Nullable
    public List<LyricLine> get(String identifier) {
        Stored stored = cache.read(path(identifier), Stored.class);
        if (stored == null || stored.version() != VERSION || stored.lines() == null || stored.lines().isEmpty()) return null;
        List<LyricLine> lines = new ArrayList<>(stored.lines().size());
        for (Line line : stored.lines()) lines.add(new LyricLine(line.timeMs(), line.text()));
        return lines;
    }

    public void put(String identifier, LyricsTimestamper.Result result) {
        List<Line> lines = new ArrayList<>(result.lines().size());
        for (LyricLine line : result.lines()) lines.add(new Line(line.timeMs(), line.text()));
        cache.write(path(identifier), new Stored(lines, result.matched(), result.total(), System.currentTimeMillis(), VERSION));
    }

    @Nullable
    public Lyrics enrich(String identifier, @Nullable Lyrics lyrics) {
        if (lyrics == null || lyrics.hasSynced() || !lyrics.hasPlain()) return lyrics;
        List<LyricLine> lines = get(identifier);
        return lines == null ? lyrics : lyrics.withAiTimestamps(lines);
    }

    private static String path(String identifier) {
        return "ai-lyrics/" + DiskCache.safeName(identifier) + ".json";
    }
}
