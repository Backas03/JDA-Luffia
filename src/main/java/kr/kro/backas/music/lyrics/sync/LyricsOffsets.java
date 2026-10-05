package kr.kro.backas.music.lyrics.sync;

import kr.kro.backas.music.cache.DiskCache;
import org.jetbrains.annotations.Nullable;

public final class LyricsOffsets {

    public static final String SOURCE_AUTO = "auto";
    public static final String SOURCE_NONE = "none";

    public record Entry(long offsetMs, String source, long at) {
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
        return cache.read(path(identifier), Entry.class);
    }

    public void put(String identifier, long offsetMs, String source) {
        cache.write(path(identifier), new Entry(offsetMs, source, System.currentTimeMillis()));
    }

    private static String path(String identifier) {
        return "lyrics-offsets/" + DiskCache.safeName(identifier) + ".json";
    }
}
