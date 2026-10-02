package kr.kro.backas.music.ai;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BatchRunnerTest {

    private static List<Integer> numbers(int count) {
        List<Integer> list = new ArrayList<>();
        for (int i = 0; i < count; i++) list.add(i);
        return list;
    }

    @Test
    void keepsBatchOrderAndReportsMonotonicProgress() throws IOException {
        List<Integer> progress = Collections.synchronizedList(new ArrayList<>());
        List<Integer> sums = BatchRunner.run(numbers(45), 10, 3, batch -> {
            try {
                Thread.sleep(20L * (5 - batch.get(0) / 10));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return batch.stream().mapToInt(Integer::intValue).sum();
        }, progress::add);
        assertEquals(List.of(45, 145, 245, 345, 210), sums);
        assertEquals(45, progress.get(progress.size() - 1));
        for (int i = 1; i < progress.size(); i++) assertTrue(progress.get(i) > progress.get(i - 1));
    }

    @Test
    void neverRunsMoreBatchesThanTheParallelism() throws IOException {
        AtomicInteger running = new AtomicInteger();
        AtomicInteger peak = new AtomicInteger();
        BatchRunner.run(numbers(80), 10, 2, batch -> {
            int now = running.incrementAndGet();
            peak.accumulateAndGet(now, Math::max);
            try {
                Thread.sleep(30);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            running.decrementAndGet();
            return batch.size();
        }, finished -> {
        });
        assertTrue(peak.get() <= 2);
        assertTrue(peak.get() >= 1);
    }

    @Test
    void propagatesTheFirstFailure() {
        IOException error = assertThrows(IOException.class, () -> BatchRunner.run(numbers(30), 10, 3, batch -> {
            if (batch.get(0) == 10) throw new IOException("server down");
            return batch.size();
        }, finished -> {
        }));
        assertEquals("server down", error.getMessage());
    }
}
