package kr.kro.backas.music.cache;

import com.fasterxml.jackson.databind.JsonNode;
import kr.kro.backas.music.lyrics.LyricLine;
import kr.kro.backas.music.lyrics.Lyrics;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DiskCacheTest {

    @TempDir
    Path directory;

    @Test
    void writesAndReadsJson() {
        DiskCache cache = new DiskCache(directory);
        cache.write("translations/a.json", Map.of("model", "luffia", "lines", Map.of("1", "안녕")));
        JsonNode node = cache.read("translations/a.json");
        assertEquals("luffia", node.path("model").asText());
        assertEquals("안녕", node.path("lines").path("1").asText());
        assertFalse(Files.exists(directory.resolve("translations/a.json.tmp")));
    }

    @Test
    void roundTripsLyricsRecords() {
        DiskCache cache = new DiskCache(directory);
        Lyrics lyrics = new Lyrics("track", "artist", null, List.of(new LyricLine(1000, "line")), false);
        cache.write("lyrics/x.json", lyrics);
        assertEquals(lyrics, cache.read("lyrics/x.json", Lyrics.class));
    }

    @Test
    void missingOrBrokenFilesReadAsNull() throws Exception {
        DiskCache cache = new DiskCache(directory);
        assertNull(cache.read("nothing.json"));
        Files.writeString(directory.resolve("broken.json"), "{not json");
        assertNull(cache.read("broken.json"));
    }

    @Test
    void refusesPathsOutsideTheCacheDirectory() {
        DiskCache cache = new DiskCache(directory);
        assertThrows(IllegalArgumentException.class, () -> cache.read("../escape.json"));
        assertTrue(DiskCache.safeName("synced-3-ab:cd/../x").matches("[A-Za-z0-9._-]+"));
    }
}
