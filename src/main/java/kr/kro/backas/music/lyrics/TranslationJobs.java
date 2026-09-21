package kr.kro.backas.music.lyrics;

import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
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
    private static final long PRIORITY_POLL_MS = 250;
    private static final Map<String, Job> JOBS = new ConcurrentHashMap<>();
    private static final AtomicInteger THREAD_COUNTER = new AtomicInteger();
    public static final ExecutorService EXECUTOR = Executors.newCachedThreadPool(runnable -> {
        Thread thread = new Thread(runnable, "lyrics-translation-" + THREAD_COUNTER.incrementAndGet());
        thread.setDaemon(true);
        return thread;
    });

    private TranslationJobs() {
    }

    public static String cacheKey(String prefix, List<String> lines) {
        StringBuilder joined = new StringBuilder();
        for (String line : lines) joined.append(line == null ? "" : line).append('\n');
        return prefix + ":" + lines.size() + ":" + Integer.toHexString(joined.toString().hashCode());
    }

    public static Job submit(TranslationClient translator, String key, List<String> lines, boolean priority,
                             @Nullable BiConsumer<Integer, String> onLine) {
        Job job = JOBS.compute(key, (k, existing) ->
                existing != null && !existing.done.isDone() ? existing : new Job(translator, k, lines));
        if (onLine != null) job.listeners.add(onLine);
        boolean fresh = job.started.compareAndSet(false, true);
        if (priority) job.promote();
        if (fresh) EXECUTOR.execute(job::run);
        return job;
    }

    private static boolean priorityActive(Job self) {
        for (Job job : JOBS.values()) {
            if (job != self && job.priority && !job.cancelled && !job.done.isDone()) return true;
        }
        return false;
    }

    public static final class Job {
        private final TranslationClient translator;
        private final String key;
        private final List<String> lines;
        private final Map<Integer, String> cache;
        private final CompletableFuture<Void> done = new CompletableFuture<>();
        private final List<BiConsumer<Integer, String>> listeners = new CopyOnWriteArrayList<>();
        private final AtomicBoolean started = new AtomicBoolean(false);
        private volatile boolean priority;
        private volatile boolean cancelled;
        private volatile boolean preempted;
        private volatile TranslationClient.Cancellation inFlight;

        private Job(TranslationClient translator, String key, List<String> lines) {
            this.translator = translator;
            this.key = key;
            this.lines = List.copyOf(lines);
            this.cache = translator.cacheFor(key);
        }

        public CompletableFuture<Void> done() {
            return done;
        }

        public Map<Integer, String> cache() {
            return cache;
        }

        public boolean isCancelled() {
            return cancelled;
        }

        public void promote() {
            if (priority) return;
            priority = true;
            for (Job other : JOBS.values()) {
                if (other != this && !other.priority) other.preempt();
            }
        }

        public void demote() {
            priority = false;
        }

        public void cancel() {
            cancelled = true;
            abort();
        }

        private void preempt() {
            preempted = true;
            abort();
        }

        private void abort() {
            TranslationClient.Cancellation current = inFlight;
            if (current != null) current.cancel();
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
            boolean waiting = false;
            try {
                while (!cancelled) {
                    if (!priority && priorityActive(this)) {
                        if (!waiting) {
                            waiting = true;
                            LOGGER.info("translation job {} waiting for the current song", key);
                        }
                        sleep(PRIORITY_POLL_MS);
                        continue;
                    }
                    waiting = false;
                    preempted = false;
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
                                (offset, text) -> deliver(indices.get(offset), sources.get(offset), text), cancellation);
                        for (int i = 0; i < indices.size(); i++) deliver(indices.get(i), sources.get(i), translated.get(i));
                        LOGGER.info("translation job {} finished: {} lines cached", key, cache.size());
                    } catch (IOException e) {
                        if (cancelled) {
                            LOGGER.info("translation job {} cancelled with {} lines cached", key, cache.size());
                            return;
                        }
                        if (preempted) {
                            LOGGER.info("lyrics translation for {} preempted, resuming later", key);
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
                done.complete(null);
            }
        }

        private static void sleep(long millis) {
            try {
                Thread.sleep(millis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
