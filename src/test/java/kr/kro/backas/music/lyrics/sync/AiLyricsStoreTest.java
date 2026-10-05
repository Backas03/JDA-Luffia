package kr.kro.backas.music.lyrics.sync;

import kr.kro.backas.music.cache.DiskCache;
import kr.kro.backas.music.lyrics.LyricLine;
import kr.kro.backas.music.lyrics.Lyrics;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AiLyricsStoreTest {

    @TempDir
    Path tempDir;

    @Test
    void storesTimestampedLinesAndAttachesThemToPlainLyrics() {
        AiLyricsStore store = new AiLyricsStore(new DiskCache(tempDir));
        assertNull(store.get("abc"));
        List<LyricLine> lines = List.of(new LyricLine(1_000, "first"), new LyricLine(4_500, "second"));
        store.put("abc", new LyricsTimestamper.Result(lines, 2, 2));
        assertEquals(lines, store.get("abc"));

        Lyrics plain = new Lyrics("song", "artist", "first\nsecond", List.of(), false);
        Lyrics enriched = store.enrich("abc", plain);
        assertTrue(enriched.aiTimed());
        assertEquals(lines, enriched.synced());
        assertFalse(store.enrich("other", plain).aiTimed());
        Lyrics synced = new Lyrics("song", "artist", null, lines, false);
        assertFalse(store.enrich("abc", synced).aiTimed());
    }
}
