package kr.kro.backas.util;

import net.dv8tion.jda.api.JDA;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

public final class BotShutdown {

    private static final Logger LOGGER = LoggerFactory.getLogger(BotShutdown.class);
    public static final Duration GRACEFUL_TIMEOUT = Duration.ofSeconds(5);
    public static final Duration FORCED_TIMEOUT = Duration.ofSeconds(2);

    public interface Stoppable {
        void shutdown();

        void shutdownNow();

        boolean awaitShutdown(Duration timeout) throws InterruptedException;
    }

    private BotShutdown() {
    }

    public static boolean stopAll(Collection<? extends JDA> bots) {
        List<Stoppable> stoppables = new ArrayList<>();
        for (JDA bot : bots) stoppables.add(adapt(bot));
        return stopAll(stoppables, GRACEFUL_TIMEOUT, FORCED_TIMEOUT);
    }

    public static boolean stopAll(List<? extends Stoppable> bots, Duration graceful, Duration forced) {
        for (Stoppable bot : bots) {
            try {
                bot.shutdown();
            } catch (RuntimeException e) {
                LOGGER.debug("shutdown request failed", e);
            }
        }
        List<Stoppable> remaining = awaitAll(bots, graceful);
        if (remaining.isEmpty()) return true;
        for (Stoppable bot : remaining) {
            try {
                bot.shutdownNow();
            } catch (RuntimeException e) {
                LOGGER.debug("forced shutdown request failed", e);
            }
        }
        return awaitAll(remaining, forced).isEmpty();
    }

    private static List<Stoppable> awaitAll(List<? extends Stoppable> bots, Duration timeout) {
        List<Stoppable> remaining = new ArrayList<>();
        long deadline = System.nanoTime() + timeout.toNanos();
        for (Stoppable bot : bots) {
            Duration left = Duration.ofNanos(Math.max(0, deadline - System.nanoTime()));
            try {
                if (!bot.awaitShutdown(left)) remaining.add(bot);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                remaining.add(bot);
            } catch (RuntimeException e) {
                LOGGER.debug("await shutdown failed", e);
                remaining.add(bot);
            }
        }
        return remaining;
    }

    private static Stoppable adapt(JDA bot) {
        return new Stoppable() {
            @Override
            public void shutdown() {
                bot.shutdown();
            }

            @Override
            public void shutdownNow() {
                bot.shutdownNow();
            }

            @Override
            public boolean awaitShutdown(Duration timeout) throws InterruptedException {
                return bot.awaitShutdown(timeout);
            }
        };
    }
}
