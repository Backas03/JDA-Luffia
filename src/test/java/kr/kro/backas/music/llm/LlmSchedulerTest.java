package kr.kro.backas.music.llm;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LlmSchedulerTest {

    private final AtomicLong now = new AtomicLong(1_000_000);

    private static LlmEndpoint gpu(String label, int slots, double... speeds) {
        LlmEndpoint endpoint = new LlmEndpoint("http://" + label, label, "luffia", slots, false);
        for (int i = 0; i < speeds.length; i++) endpoint.speed().record(i + 1, speeds[i]);
        return endpoint;
    }

    private LlmScheduler scheduler(LlmEndpoint... endpoints) {
        return new LlmScheduler(List.of(endpoints), now::get);
    }

    private static LlmLease take(LlmScheduler scheduler, LlmPriority priority) throws IOException {
        return scheduler.acquire(() -> priority, 500, Set.of(), 0);
    }

    @Test
    void userRequestGoesToTheFastestIdleGpu() throws IOException {
        LlmEndpoint rtx = gpu("5080", 3, 85, 60, 45);
        LlmEndpoint radeon = gpu("7800XT", 2, 60, 45);
        assertSame(rtx, take(scheduler(rtx, radeon), LlmPriority.INTERACTIVE).endpoint());
    }

    @Test
    void secondRequestMovesToTheOtherGpuInsteadOfSlowingTheFirst() throws IOException {
        LlmEndpoint rtx = gpu("5080", 3, 85, 60, 45);
        LlmEndpoint radeon = gpu("7800XT", 2, 60, 45);
        LlmScheduler scheduler = scheduler(rtx, radeon);
        take(scheduler, LlmPriority.INTERACTIVE);
        assertSame(radeon, take(scheduler, LlmPriority.INTERACTIVE).endpoint());
    }

    @Test
    void fastGpuStillWinsWhenTheOtherIsMuchSlower() throws IOException {
        LlmEndpoint rtx = gpu("5080", 3, 85, 80, 70);
        LlmEndpoint slow = gpu("slow", 2, 15, 10);
        LlmScheduler scheduler = scheduler(rtx, slow);
        take(scheduler, LlmPriority.BACKGROUND);
        assertSame(rtx, take(scheduler, LlmPriority.BACKGROUND).endpoint());
    }

    @Test
    void backgroundDoesNotSqueezeIntoAGpuServingAUserRequest() throws IOException {
        LlmEndpoint rtx = gpu("5080", 3, 85, 50, 40);
        LlmScheduler scheduler = scheduler(rtx);
        take(scheduler, LlmPriority.INTERACTIVE);
        assertThrows(IOException.class, () -> take(scheduler, LlmPriority.BACKGROUND));
        assertSame(rtx, take(scheduler, LlmPriority.INTERACTIVE).endpoint());
    }

    @Test
    void cpuFallbackIsOnlyUsedWhenEveryGpuIsDown() throws IOException {
        LlmEndpoint rtx = gpu("5080", 1, 85);
        LlmEndpoint cpu = new LlmEndpoint("http://cpu", "cpu", "", 1, true);
        LlmScheduler scheduler = scheduler(rtx, cpu);
        assertSame(rtx, take(scheduler, LlmPriority.INTERACTIVE).endpoint());
        assertThrows(IOException.class, () -> take(scheduler, LlmPriority.INTERACTIVE));
        rtx.markFailed(now.get());
        assertSame(cpu, scheduler.acquire(() -> LlmPriority.INTERACTIVE, 500, Set.of(), 0).endpoint());
    }

    @Test
    void failedGpuIsRetriedAfterTheCooldown() throws IOException {
        LlmEndpoint rtx = gpu("5080", 1, 85);
        LlmEndpoint radeon = gpu("7800XT", 1, 60);
        LlmScheduler scheduler = scheduler(rtx, radeon);
        rtx.markFailed(now.get());
        assertSame(radeon, take(scheduler, LlmPriority.INTERACTIVE).endpoint());
        now.addAndGet(LlmScheduler.RETRY_FAILED_MS);
        assertSame(rtx, take(scheduler, LlmPriority.INTERACTIVE).endpoint());
    }

    @Test
    void userRequestPreemptsBackgroundWhenEverySlotIsBusy() throws IOException {
        LlmEndpoint rtx = gpu("5080", 1, 85);
        LlmScheduler scheduler = scheduler(rtx);
        LlmLease background = take(scheduler, LlmPriority.BACKGROUND);
        AtomicBoolean preempted = new AtomicBoolean();
        background.onPreempt(() -> {
            preempted.set(true);
            background.close();
        });
        LlmLease user = scheduler.acquire(() -> LlmPriority.INTERACTIVE, 500, Set.of(), 1_000);
        assertTrue(preempted.get());
        assertTrue(background.wasPreempted());
        assertSame(rtx, user.endpoint());
    }

    @Test
    void releasedSlotsCanBeReused() throws IOException {
        LlmEndpoint rtx = gpu("5080", 1, 85);
        LlmScheduler scheduler = scheduler(rtx);
        LlmLease first = take(scheduler, LlmPriority.INTERACTIVE);
        first.close();
        first.close();
        assertSame(rtx, take(scheduler, LlmPriority.INTERACTIVE).endpoint());
        assertEquals(1, scheduler.snapshot().get(0).inUse());
    }

    @Test
    void summaryListsEachBusyGpuOnItsOwnLine() throws IOException {
        LlmEndpoint rtx = gpu("NVIDIA GeForce RTX 5080", 3, 85, 60, 45);
        LlmEndpoint radeon = gpu("AMD Radeon RX 7800 XT", 2, 60, 45);
        LlmScheduler scheduler = scheduler(rtx, radeon);
        LlmLease first = take(scheduler, LlmPriority.INTERACTIVE);
        first.reportLiveSpeed(80);
        assertEquals("NVIDIA GeForce RTX 5080 | 80 token/s", scheduler.summary());
        LlmLease second = take(scheduler, LlmPriority.INTERACTIVE);
        second.reportLiveSpeed(58);
        assertEquals("NVIDIA GeForce RTX 5080 | 80 token/s\nAMD Radeon RX 7800 XT | 58 token/s", scheduler.summary());
    }
}
