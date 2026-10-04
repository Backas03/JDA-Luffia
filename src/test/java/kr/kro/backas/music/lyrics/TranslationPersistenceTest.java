package kr.kro.backas.music.lyrics;

import kr.kro.backas.music.cache.DiskCache;
import kr.kro.backas.music.llm.SpeedModel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TranslationPersistenceTest {

    @TempDir
    Path directory;

    @Test
    void translationsSurviveARestart() {
        String key = TranslationJobs.cacheKey("synced", List.of("夜明け前の街を", "君の声が"));
        TranslationClient first = new TranslationClient(null, new DiskCache(directory));
        Map<Integer, String> lines = first.cacheFor(key);
        lines.put(0, "새벽 전의 거리를");
        lines.put(1, "");
        first.persist(key);

        TranslationClient restarted = new TranslationClient(null, new DiskCache(directory));
        Map<Integer, String> loaded = restarted.cacheFor(key);
        assertEquals("새벽 전의 거리를", loaded.get(0));
        assertFalse(loaded.containsKey(1));
    }

    @Test
    void translationsFromAnOlderPromptVersionAreIgnored() {
        String key = TranslationJobs.cacheKey("synced", List.of("夜明け前の街を"));
        DiskCache cache = new DiskCache(directory);
        cache.write("translations/" + DiskCache.safeName(key) + ".json",
                Map.of("model", "luffia", "lines", Map.of("0", "옛 번역")));

        TranslationClient client = new TranslationClient(null, cache);
        assertTrue(client.cacheFor(key).isEmpty());
    }

    @Test
    void cacheKeysAreStableFileSafeAndContentSensitive() {
        String key = TranslationJobs.cacheKey("synced", List.of("a", "b"));
        assertEquals(key, TranslationJobs.cacheKey("synced", List.of("a", "b")));
        assertNotEquals(key, TranslationJobs.cacheKey("synced", List.of("a", "c")));
        assertTrue(key.matches("[A-Za-z0-9-]+"));
    }

    @Test
    void learnedGpuSpeedsSurviveARestartAndKeepTuning() {
        String urls = "http://127.0.0.1:1|gpu|test-model|2";
        TranslationClient first = new TranslationClient(urls, new DiskCache(directory));
        SpeedModel learned = first.scheduler().endpoints().get(0).speed();
        learned.record(1, 70);
        learned.record(2, 50);
        first.persistSpeeds();

        SpeedModel restarted = new TranslationClient(urls, new DiskCache(directory)).scheduler().endpoints().get(0).speed();
        assertEquals(70, restarted.estimate(1), 0.001);
        assertEquals(50, restarted.estimate(2), 0.001);
        restarted.record(1, 90);
        assertEquals(76, restarted.estimate(1), 0.001);
    }

    @Test
    void savedSpeedsOfAnotherModelAreNotReused() {
        TranslationClient first = new TranslationClient("http://127.0.0.1:1|gpu|test-model", new DiskCache(directory));
        first.scheduler().endpoints().get(0).speed().record(1, 70);
        first.persistSpeeds();

        TranslationClient other = new TranslationClient("http://127.0.0.1:1|gpu|other-model", new DiskCache(directory));
        assertFalse(other.scheduler().endpoints().get(0).speed().isMeasured(1));
    }
}
