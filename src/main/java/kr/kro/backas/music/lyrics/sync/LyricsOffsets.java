package kr.kro.backas.music.lyrics.sync;

import kr.kro.backas.music.cache.DiskCache;
import org.jetbrains.annotations.Nullable;

public final class LyricsOffsets {

    public static final String SOURCE_AUTO = "auto";
    public static final String SOURCE_NONE = "none";
    public static final int VERSION = 2;
    static final long NONE_RETRY_MS = 6 * 60 * 60 * 1000L;

    public record Entry(long offsetMs, String source, long at, int version) {
    }

    private static final LyricsOffsets DEFAULT = new LyricsOffsets(DiskCache.defaultCache());

    private final DiskCache cache;

    public LyricsOffsets(DiskCache cache) {
        this.cache = cache;
    }

    public static LyricsOffsets defaultStore() {
        return DEFAULT;
    }

    @Nullable
    public Entry get(String identifier) {
        Entry entry = cache.read(path(identifier), Entry.class);
        if (entry == null || entry.version() != VERSION) return null;
        if (SOURCE_NONE.equals(entry.source()) && System.currentTimeMillis() - entry.at() > NONE_RETRY_MS) return null;
        return entry;
    }

    public void put(String identifier, long offsetMs, String source) {
        cache.write(path(identifier), new Entry(offsetMs, source, System.currentTimeMillis(), VERSION));
    }

    private static String path(String identifier) {
        return "lyrics-offsets/" + DiskCache.safeName(identifier) + ".json";
    }
}
