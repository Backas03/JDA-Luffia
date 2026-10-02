package kr.kro.backas.music.ai;

import kr.kro.backas.music.lyrics.TranslationJobs;

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

    private BatchRunner() {
    }

    static <T, R> List<R> run(List<T> items, int batchSize, int parallelism, Call<T, R> call, IntConsumer onItemsDone)
            throws IOException {
        Semaphore permits = new Semaphore(Math.max(1, parallelism));
        Object progressLock = new Object();
        int[] finished = new int[1];
        List<CompletableFuture<R>> futures = new ArrayList<>();
        for (int from = 0; from < items.size(); from += batchSize) {
            List<T> batch = items.subList(from, Math.min(from + batchSize, items.size()));
            try {
                permits.acquire();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("interrupted while scheduling ai batches", e);
            }
            futures.add(CompletableFuture.supplyAsync(() -> {
                try {
                    return call.run(batch);
                } catch (IOException e) {
                    throw new CompletionException(e);
                } finally {
                    permits.release();
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
