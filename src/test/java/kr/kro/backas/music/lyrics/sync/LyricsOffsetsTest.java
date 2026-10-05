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
}
