package kr.kro.backas.music.lyrics;

import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import kr.kro.backas.music.llm.LlmPriority;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;

public final class TranslationJobs {

    private static final Logger LOGGER = LoggerFactory.getLogger(TranslationJobs.class);
    private static final int CHUNK = 400;
    private static final int MAX_SONG_CONTEXT = 150;
    private static final Map<String, Job> JOBS = new ConcurrentHashMap<>();
    private static final AtomicInteger THREAD_COUNTER = new AtomicInteger();
    public static final ExecutorService EXECUTOR = Executors.newCachedThreadPool(runnable -> {
        Thread thread = new Thread(runnable, "lyrics-translation-" + THREAD_COUNTER.incrementAndGet());
        thread.setDaemon(true);
        return thread;
    });

    private TranslationJobs() {
    }

    public static String songContext(AudioTrack track) {
        String context = track.getInfo().title + " - " + track.getInfo().author;
        return context.length() > MAX_SONG_CONTEXT ? context.substring(0, MAX_SONG_CONTEXT) : context;
    }

    public static String cacheKey(String prefix, List<String> lines) {
        StringBuilder joined = new StringBuilder();
        for (String line : lines) joined.append(line == null ? "" : line).append('\n');
        return prefix + "-" + lines.size() + "-" + sha256(joined.toString());
    }

    static String sha256(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public static Job submit(TranslationClient translator, String key, List<String> lines, boolean priority,
                             @Nullable BiConsumer<Integer, String> onLine) {
        return submit(translator, key, lines, priority, onLine, null);
    }

    public static Job submit(TranslationClient translator, String key, List<String> lines, boolean priority,
                             @Nullable BiConsumer<Integer, String> onLine, @Nullable String songContext) {
        Job job = JOBS.compute(key, (k, existing) -> {
            if (existing != null && !existing.done.isDone() && !existing.cancelled) return existing;
            Job created = new Job(translator, k, lines, songContext);
            if (priority) created.cache.values().removeIf(String::isBlank);
            return created;
        });
        if (onLine != null) job.listeners.add(onLine);
        boolean fresh = job.started.compareAndSet(false, true);
        if (priority) job.promote();
        if (fresh) EXECUTOR.execute(job::run);
        return job;
    }

    public static final class Job {
        private final TranslationClient translator;
        private final String key;
        private final List<String> lines;
        private final String songContext;
        private final Map<Integer, String> cache;
        private final CompletableFuture<Void> done = new CompletableFuture<>();
        private final List<BiConsumer<Integer, String>> listeners = new CopyOnWriteArrayList<>();
        private final AtomicBoolean started = new AtomicBoolean(false);
        private volatile boolean priority;
        private volatile boolean cancelled;
        private volatile TranslationClient.Cancellation inFlight;

        private Job(TranslationClient translator, String key, List<String> lines, @Nullable String songContext) {
            this.translator = translator;
            this.key = key;
            this.lines = List.copyOf(lines);
            this.songContext = songContext;
            this.cache = translator.cacheFor(key);
        }

        public CompletableFuture<Void> done() {
            return done;
        }

        public Map<Integer, String> cache() {
            return cache;
        }

        public float progress() {
            int total = 0;
            int done = 0;
            for (int i = 0; i < lines.size(); i++) {
                String line = lines.get(i);
                if (line == null || !LyricsLanguage.needsTranslation(line)) continue;
                total++;
                if (cache.containsKey(i)) done++;
            }
            return total == 0 ? 1f : (float) done / total;
        }

        public boolean isCancelled() {
            return cancelled;
        }

        public void promote() {
            if (priority) return;
            priority = true;
            translator.wakeScheduler();
        }

        public void demote() {
            priority = false;
        }

        public void cancel() {
            cancelled = true;
            TranslationClient.Cancellation current = inFlight;
            if (current != null) current.cancel();
        }

        private LlmPriority schedulingPriority() {
            return priority ? LlmPriority.INTERACTIVE : LlmPriority.BACKGROUND;
        }

        private List<Integer> pending() {
            List<Integer> pending = new ArrayList<>();
            for (int i = 0; i < lines.size(); i++) {
                String line = lines.get(i);
                if (!cache.containsKey(i) && line != null && LyricsLanguage.needsTranslation(line)) pending.add(i);
            }
            return pending;
        }

        private void deliver(int index, String source, @Nullable String text) {
            if (text == null || text.isBlank() || text.equals(source)) return;
            publish(index, text);
        }

        private void keepOriginal(int index, String source) {
            if (!source.isBlank()) publish(index, source);
        }

        private void publish(int index, String text) {
            if (text.equals(cache.put(index, text))) return;
            for (BiConsumer<Integer, String> listener : listeners) {
                try {
                    listener.accept(index, text);
                } catch (RuntimeException e) {
                    LOGGER.debug("translation listener failed", e);
                }
            }
        }

        private void run() {
            try {
                while (!cancelled) {
                    List<Integer> pending = pending();
                    if (pending.isEmpty()) {
                        LOGGER.info("translation job {} has nothing left to translate", key);
                        return;
                    }
                    LOGGER.info("translation job {} started: {} lines, priority={}", key, pending.size(), priority);
                    List<Integer> indices = new ArrayList<>(pending.subList(0, Math.min(CHUNK, pending.size())));
                    List<String> sources = new ArrayList<>(indices.size());
                    for (int index : indices) sources.add(lines.get(index));
                    TranslationClient.Cancellation cancellation = new TranslationClient.Cancellation();
                    inFlight = cancellation;
                    try {
                        if (cancelled) return;
                        List<String> translated = translator.translate("auto", sources,
                                (offset, text) -> deliver(indices.get(offset), sources.get(offset), text), cancellation,
                                songContext, this::schedulingPriority);
                        int untranslated = 0;
                        for (int i = 0; i < indices.size(); i++) {
                            if (LyricsLanguage.isLatinOnly(sources.get(i)) && sources.get(i).equals(translated.get(i))) {
                                keepOriginal(indices.get(i), sources.get(i));
                            } else {
                                deliver(indices.get(i), sources.get(i), translated.get(i));
                            }
                            if (!cache.containsKey(indices.get(i))) {
                                cache.put(indices.get(i), "");
                                untranslated++;
                            }
                        }
                        LOGGER.info("translation job {} finished: {} lines cached, {} left untranslated", key, cache.size(), untranslated);
                    } catch (IOException e) {
                        if (cancelled) {
                            LOGGER.info("translation job {} cancelled with {} lines cached", key, cache.size());
                            return;
                        }
                        if (cancellation.wasPreempted()) {
                            LOGGER.info("lyrics translation for {} gave its gpu to a user request, resuming", key);
                            continue;
                        }
                        LOGGER.warn("lyrics translation failed for {} ({} lines)", key, indices.size(), e);
                        return;
                    } finally {
                        inFlight = null;
                    }
                }
            } finally {
                JOBS.remove(key, this);
                translator.persist(key);
                done.complete(null);
            }
        }
    }
}
