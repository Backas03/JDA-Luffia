package kr.kro.backas.music.ai;

import kr.kro.backas.music.llm.LlmEndpoint;
import kr.kro.backas.music.lyrics.TranslationClient;
import kr.kro.backas.music.lyrics.TranslationJobs;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Semaphore;
import java.util.function.IntConsumer;

final class BatchRunner {

    interface Call<T, R> {
        R run(List<T> batch) throws IOException;
    }

    interface LaneCall<L, T, R> {
        R run(@Nullable L lane, List<T> batch) throws IOException;
    }

    record Lane<L>(@Nullable L key, int parallelism) {
    }

    private BatchRunner() {
    }

    static List<Lane<LlmEndpoint>> lanes(TranslationClient client) {
        List<Lane<LlmEndpoint>> lanes = new ArrayList<>();
        for (LlmEndpoint gpu : client.liveGpus()) lanes.add(new Lane<>(gpu, gpu.slots()));
        if (lanes.isEmpty()) lanes.add(new Lane<>(null, client.parallelism()));
        return lanes;
    }

    static <T, R> List<R> run(List<T> items, int batchSize, int parallelism, Call<T, R> call, IntConsumer onItemsDone)
            throws IOException {
        return run(items, batchSize, List.of(new Lane<Void>(null, parallelism)), (lane, batch) -> call.run(batch), onItemsDone);
    }

    static <L, T, R> List<R> run(List<T> items, int batchSize, List<Lane<L>> lanes, LaneCall<L, T, R> call,
                                 IntConsumer onItemsDone) throws IOException {
        List<Semaphore> permits = new ArrayList<>(lanes.size());
        for (Lane<L> lane : lanes) permits.add(new Semaphore(Math.max(1, lane.parallelism())));
        Object progressLock = new Object();
        int[] finished = new int[1];
        List<CompletableFuture<R>> futures = new ArrayList<>();
        int batchIndex = 0;
        for (int from = 0; from < items.size(); from += batchSize) {
            List<T> batch = items.subList(from, Math.min(from + batchSize, items.size()));
            int laneIndex = batchIndex++ % lanes.size();
            futures.add(CompletableFuture.supplyAsync(() -> {
                try {
                    permits.get(laneIndex).acquire();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new CompletionException(new IOException("interrupted while waiting for an ai batch slot", e));
                }
                try {
                    return call.run(lanes.get(laneIndex).key(), batch);
                } catch (IOException e) {
                    throw new CompletionException(e);
                } finally {
                    permits.get(laneIndex).release();
                    synchronized (progressLock) {
                        finished[0] += batch.size();
                        onItemsDone.accept(finished[0]);
                    }
                }
            }, TranslationJobs.EXECUTOR));
        }
        List<R> results = new ArrayList<>(futures.size());
        IOException failure = null;
        for (CompletableFuture<R> future : futures) {
            try {
                results.add(future.join());
            } catch (CompletionException e) {
                results.add(null);
                if (failure == null) {
                    failure = e.getCause() instanceof IOException io ? io : new IOException(e.getCause());
                }
            }
        }
        if (failure != null) throw failure;
        return results;
    }
}
