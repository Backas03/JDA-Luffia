package kr.kro.backas.music.lyrics.sync;

import kr.kro.backas.music.cache.DiskCache;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class LyricsOffsetsTest {

    @TempDir
    Path tempDir;

    @Test
    void remembersOffsetsPerTrackIdentifier() {
        LyricsOffsets store = new LyricsOffsets(new DiskCache(tempDir));
        assertNull(store.get("dQw4w9WgXcQ"));
        store.put("dQw4w9WgXcQ", -2_500, LyricsOffsets.SOURCE_AUTO);
        store.put("https://open.spotify.com/track/abc", 0, LyricsOffsets.SOURCE_NONE);
        LyricsOffsets.Entry entry = store.get("dQw4w9WgXcQ");
        assertNotNull(entry);
        assertEquals(-2_500, entry.offsetMs());
        assertEquals(LyricsOffsets.SOURCE_AUTO, entry.source());
        LyricsOffsets.Entry none = store.get("https://open.spotify.com/track/abc");
        assertNotNull(none);
        assertEquals(LyricsOffsets.SOURCE_NONE, none.source());
        assertNull(store.get("other"));
    }

    @Test
    void ignoresEntriesWrittenByOlderAlignersOrStaleNoMatchResults() {
        DiskCache cache = new DiskCache(tempDir);
        LyricsOffsets store = new LyricsOffsets(cache);
        cache.write("lyrics-offsets/old.json", new LyricsOffsets.Entry(-1_000, LyricsOffsets.SOURCE_AUTO, System.currentTimeMillis(), LyricsOffsets.VERSION - 1));
        assertNull(store.get("old"));
        cache.write("lyrics-offsets/stale.json", new LyricsOffsets.Entry(0, LyricsOffsets.SOURCE_NONE,
                System.currentTimeMillis() - LyricsOffsets.NONE_RETRY_MS - 1, LyricsOffsets.VERSION));
        assertNull(store.get("stale"));
        cache.write("lyrics-offsets/fresh.json", new LyricsOffsets.Entry(0, LyricsOffsets.SOURCE_NONE,
                System.currentTimeMillis(), LyricsOffsets.VERSION));
        assertNotNull(store.get("fresh"));
    }
}
