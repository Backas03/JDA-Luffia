package kr.kro.backas.music.llm;

import java.util.function.Supplier;

public final class LlmLease implements AutoCloseable {

    private static final long LIVE_FRESH_MS = 5_000;

    private final LlmScheduler scheduler;
    private final LlmEndpoint endpoint;
    private final Supplier<LlmPriority> priority;
    private final int estimatedTokens;
    private final long startedAt;
    private double concurrencyTime;
    private boolean released;
    private volatile Runnable preemptAction;
    private volatile boolean preempted;
    private volatile double liveTokensPerSecond;
    private volatile long liveUpdatedAt;

    LlmLease(LlmScheduler scheduler, LlmEndpoint endpoint, Supplier<LlmPriority> priority, int estimatedTokens, long startedAt) {
        this.scheduler = scheduler;
        this.endpoint = endpoint;
        this.priority = priority;
        this.estimatedTokens = Math.max(1, estimatedTokens);
        this.startedAt = startedAt;
    }

    public LlmEndpoint endpoint() {
        return endpoint;
    }

    public LlmPriority priority() {
        LlmPriority current = priority.get();
        return current == null ? LlmPriority.BACKGROUND : current;
    }

    public void onPreempt(Runnable action) {
        preemptAction = action;
        if (preempted) action.run();
    }

    public boolean wasPreempted() {
        return preempted;
    }

    public void reportLiveSpeed(double tokensPerSecond) {
        liveTokensPerSecond = tokensPerSecond;
        liveUpdatedAt = System.currentTimeMillis();
    }

    public void complete(long tokens, long elapsedMs) {
        scheduler.recordCompletion(this, tokens, elapsedMs);
    }

    @Override
    public void close() {
        scheduler.release(this);
    }

    boolean isPreemptable() {
        return preemptAction != null && !preempted && priority() == LlmPriority.BACKGROUND;
    }

    boolean isPreempting() {
        return preempted && !released;
    }

    void preempt() {
        preempted = true;
        Runnable action = preemptAction;
        if (action != null) action.run();
    }

    double liveTokensPerSecond(long now) {
        return now - liveUpdatedAt <= LIVE_FRESH_MS ? liveTokensPerSecond : -1;
    }

    double remainingTokens(double tokensPerSecond, long now) {
        double done = tokensPerSecond * Math.max(0, now - startedAt) / 1000.0;
        return Math.max(estimatedTokens - done, estimatedTokens * 0.1);
    }

    long startedAt() {
        return startedAt;
    }

    void addConcurrencyTime(int concurrency, long millis) {
        concurrencyTime += (double) concurrency * Math.max(0, millis);
    }

    double concurrencyTime() {
        return concurrencyTime;
    }

    boolean markReleased() {
        if (released) return false;
        released = true;
        return true;
    }
}
