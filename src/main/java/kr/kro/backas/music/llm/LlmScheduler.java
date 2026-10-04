package kr.kro.backas.music.llm;

import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

public final class LlmScheduler {

    private static final Logger LOGGER = LoggerFactory.getLogger(LlmScheduler.class);
    public static final long RETRY_FAILED_MS = 30_000;
    static final double INTERACTIVE_HARM_WEIGHT = 3.0;
    static final double MAX_INTERACTIVE_SLOWDOWN = 0.3;
    private static final long DEFAULT_WAIT_MS = 180_000;
    private static final long WAIT_TICK_MS = 500;
    private static final long LAST_SPEED_FRESH_MS = 30_000;

    public record EndpointStatus(String label, String modelName, boolean fallback, boolean available, int inUse, int slots,
                                 double[] speeds, boolean[] measured, double currentTokensPerSecond) {
    }

    record Choice(@Nullable LlmEndpoint endpoint, boolean anyUsable) {
    }

    private record Waiter(Supplier<LlmPriority> priority, long sequence) {
        LlmPriority current() {
            LlmPriority value = priority.get();
            return value == null ? LlmPriority.BACKGROUND : value;
        }
    }

    private final List<LlmEndpoint> endpoints;
    private final LongSupplier clock;
    private final Object lock = new Object();
    private final List<Waiter> waiters = new ArrayList<>();
    private long sequence;
    private volatile LlmEndpoint lastUsed;

    public LlmScheduler(List<LlmEndpoint> endpoints) {
        this(endpoints, System::currentTimeMillis);
    }

    LlmScheduler(List<LlmEndpoint> endpoints, LongSupplier clock) {
        this.endpoints = List.copyOf(endpoints);
        this.clock = clock;
    }

    public List<LlmEndpoint> endpoints() {
        return endpoints;
    }

    public LlmLease acquire(Supplier<LlmPriority> priority, int estimatedTokens, Set<LlmEndpoint> excluded) throws IOException {
        return acquire(priority, estimatedTokens, excluded, DEFAULT_WAIT_MS);
    }

    LlmLease acquire(Supplier<LlmPriority> priority, int estimatedTokens, Set<LlmEndpoint> excluded, long maxWaitMs)
            throws IOException {
        long deadline = clock.getAsLong() + maxWaitMs;
        synchronized (lock) {
            Waiter waiter = new Waiter(priority, sequence++);
            waiters.add(waiter);
            try {
                while (true) {
                    if (isNext(waiter)) {
                        LlmPriority current = waiter.current();
                        Choice choice = choose(current, estimatedTokens, excluded);
                        if (choice.endpoint() != null) return grant(choice.endpoint(), priority, estimatedTokens);
                        if (!choice.anyUsable()) throw new IOException("no translator endpoint available");
                        if (current == LlmPriority.INTERACTIVE) preemptBackground(excluded);
                    }
                    long remaining = deadline - clock.getAsLong();
                    if (remaining <= 0) throw new IOException("timed out waiting for a free ai server");
                    lock.wait(Math.min(remaining, WAIT_TICK_MS));
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("interrupted while waiting for an ai server", e);
            } finally {
                waiters.remove(waiter);
                lock.notifyAll();
            }
        }
    }

    public void wake() {
        synchronized (lock) {
            lock.notifyAll();
        }
    }

    Choice choose(LlmPriority priority, int estimatedTokens, Set<LlmEndpoint> excluded) {
        long now = clock.getAsLong();
        List<LlmEndpoint> usable = new ArrayList<>();
        for (LlmEndpoint endpoint : endpoints) {
            if (!excluded.contains(endpoint) && endpoint.isAvailable(now, RETRY_FAILED_MS) && !endpoint.isFallback()) {
                usable.add(endpoint);
            }
        }
        if (usable.isEmpty()) {
            for (LlmEndpoint endpoint : endpoints) {
                if (!excluded.contains(endpoint) && endpoint.isAvailable(now, RETRY_FAILED_MS)) usable.add(endpoint);
            }
        }
        if (usable.isEmpty()) return new Choice(null, false);
        LlmEndpoint best = null;
        double bestCost = Double.MAX_VALUE;
        for (LlmEndpoint endpoint : usable) {
            int running = endpoint.leases.size();
            if (running >= endpoint.slots()) continue;
            double before = endpoint.speed().estimate(Math.max(1, running));
            double after = endpoint.speed().estimate(running + 1);
            double cost = Math.max(1, estimatedTokens) / after;
            boolean blocked = false;
            for (LlmLease lease : endpoint.leases) {
                boolean userFacing = lease.priority() == LlmPriority.INTERACTIVE;
                if (priority == LlmPriority.BACKGROUND && userFacing && 1 - after / before > MAX_INTERACTIVE_SLOWDOWN) {
                    blocked = true;
                    break;
                }
                double extra = lease.remainingTokens(before, now) * (1 / after - 1 / before);
                cost += extra * (userFacing ? INTERACTIVE_HARM_WEIGHT : 1.0);
            }
            if (blocked) continue;
            if (cost < bestCost) {
                bestCost = cost;
                best = endpoint;
            }
        }
        return new Choice(best, true);
    }

    private boolean isNext(Waiter waiter) {
        LlmPriority mine = waiter.current();
        for (Waiter other : waiters) {
            if (other == waiter) continue;
            LlmPriority theirs = other.current();
            if (theirs.ordinal() < mine.ordinal()) return false;
            if (theirs == mine && other.sequence() < waiter.sequence()) return false;
        }
        return true;
    }

    private LlmLease grant(LlmEndpoint endpoint, Supplier<LlmPriority> priority, int estimatedTokens) {
        long now = clock.getAsLong();
        accumulate(endpoint, now);
        LlmLease lease = new LlmLease(this, endpoint, priority, estimatedTokens, now);
        endpoint.leases.add(lease);
        lastUsed = endpoint;
        return lease;
    }

    void release(LlmLease lease) {
        synchronized (lock) {
            if (!lease.markReleased()) return;
            accumulate(lease.endpoint(), clock.getAsLong());
            lease.endpoint().leases.remove(lease);
            lock.notifyAll();
        }
    }

    void recordCompletion(LlmLease lease, long tokens, long elapsedMs) {
        if (tokens < 8 || elapsedMs < 500) return;
        double tokensPerSecond = tokens * 1000.0 / elapsedMs;
        int concurrency;
        synchronized (lock) {
            long now = clock.getAsLong();
            accumulate(lease.endpoint(), now);
            long alive = Math.max(1, now - lease.startedAt());
            concurrency = (int) Math.max(1, Math.round(lease.concurrencyTime() / alive));
        }
        lease.endpoint().speed().record(concurrency, tokensPerSecond);
        lease.endpoint().recordLastSpeed(tokensPerSecond, clock.getAsLong());
        LOGGER.debug("{} ran {} token/s with {} concurrent request(s)", lease.endpoint().label(),
                Math.round(tokensPerSecond), concurrency);
    }

    private void preemptBackground(Set<LlmEndpoint> excluded) {
        long now = clock.getAsLong();
        LlmLease victim = null;
        double victimSpeed = -1;
        for (LlmEndpoint endpoint : endpoints) {
            if (excluded.contains(endpoint) || !endpoint.isAvailable(now, RETRY_FAILED_MS)) continue;
            for (LlmLease lease : endpoint.leases) {
                if (lease.isPreempting()) return;
                if (!lease.isPreemptable()) continue;
                double speed = endpoint.speed().estimate(1) - (endpoint.isFallback() ? 1_000_000 : 0);
                if (speed > victimSpeed) {
                    victimSpeed = speed;
                    victim = lease;
                }
            }
        }
        if (victim != null) {
            LOGGER.info("preempting a background request on {} for a user request", victim.endpoint().label());
            victim.preempt();
        }
    }

    private void accumulate(LlmEndpoint endpoint, long now) {
        long elapsed = now - endpoint.lastChangeAt;
        int running = endpoint.leases.size();
        for (LlmLease lease : endpoint.leases) lease.addConcurrencyTime(running, elapsed);
        endpoint.lastChangeAt = now;
    }

    public boolean hasUsableGpu() {
        long now = clock.getAsLong();
        for (LlmEndpoint endpoint : endpoints) {
            if (!endpoint.isFallback() && endpoint.isAvailable(now, RETRY_FAILED_MS)) return true;
        }
        return false;
    }

    public boolean hasLiveEndpoint() {
        for (LlmEndpoint endpoint : endpoints) {
            if (!endpoint.isFailed()) return true;
        }
        return false;
    }

    public int parallelism() {
        long now = clock.getAsLong();
        int slots = 0;
        for (LlmEndpoint endpoint : endpoints) {
            if (!endpoint.isFallback() && endpoint.isAvailable(now, RETRY_FAILED_MS)) slots += endpoint.slots();
        }
        return Math.max(1, slots);
    }

    public List<EndpointStatus> snapshot() {
        synchronized (lock) {
            long now = clock.getAsLong();
            List<EndpointStatus> statuses = new ArrayList<>();
            for (LlmEndpoint endpoint : endpoints) {
                int levels = Math.min(SpeedModel.MAX_LEVEL, Math.max(endpoint.slots(), 1));
                double[] speeds = new double[levels];
                boolean[] measured = new boolean[levels];
                for (int level = 1; level <= levels; level++) {
                    speeds[level - 1] = endpoint.speed().estimate(level);
                    measured[level - 1] = endpoint.speed().isMeasured(level);
                }
                statuses.add(new EndpointStatus(endpoint.label(), endpoint.modelName(), endpoint.isFallback(),
                        endpoint.isAvailable(now, RETRY_FAILED_MS) && !endpoint.isFailed(), endpoint.leases.size(),
                        endpoint.slots(), speeds, measured, currentSpeed(endpoint, now)));
            }
            return statuses;
        }
    }

    public String summary() {
        List<EndpointStatus> statuses = snapshot();
        List<EndpointStatus> busy = statuses.stream().filter(status -> status.inUse() > 0).toList();
        if (busy.isEmpty()) {
            LlmEndpoint last = lastUsed;
            String label = last != null ? last.label() : endpoints.isEmpty() ? "" : endpoints.get(0).label();
            return (label.isBlank() ? "" : label + " | ") + "0 token/s";
        }
        StringBuilder lines = new StringBuilder();
        for (EndpointStatus status : busy) {
            if (!lines.isEmpty()) lines.append('\n');
            lines.append(status.label()).append(" | ").append(Math.round(status.currentTokensPerSecond())).append(" token/s");
        }
        return lines.toString();
    }

    public LlmEndpoint lastUsed() {
        return lastUsed;
    }

    private double currentSpeed(LlmEndpoint endpoint, long now) {
        int running = endpoint.leases.size();
        if (running == 0) return 0;
        double total = 0;
        for (LlmLease lease : endpoint.leases) {
            double live = lease.liveTokensPerSecond(now);
            if (live < 0) live = endpoint.lastTokensPerSecond(now, LAST_SPEED_FRESH_MS);
            if (live < 0) live = endpoint.speed().estimate(running);
            total += live;
        }
        return total;
    }
}
