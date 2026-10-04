package kr.kro.backas.util;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BotShutdownTest {

    private static final class FakeBot implements BotShutdown.Stoppable {
        private final boolean stopsGracefully;
        private final boolean stopsWhenForced;
        private final StringBuilder calls;
        private boolean forced;

        FakeBot(boolean stopsGracefully, boolean stopsWhenForced, StringBuilder calls) {
            this.stopsGracefully = stopsGracefully;
            this.stopsWhenForced = stopsWhenForced;
            this.calls = calls;
        }

        @Override
        public void shutdown() {
            calls.append("shutdown ");
        }

        @Override
        public void shutdownNow() {
            forced = true;
            calls.append("now ");
        }

        @Override
        public boolean awaitShutdown(Duration timeout) {
            calls.append("await(").append(timeout.toMillis()).append(") ");
            return forced ? stopsWhenForced : stopsGracefully;
        }
    }

    @Test
    void requestsShutdownOnEveryBotBeforeWaitingOnAny() {
        StringBuilder calls = new StringBuilder();
        List<FakeBot> bots = List.of(new FakeBot(true, true, calls), new FakeBot(true, true, calls));
        assertTrue(BotShutdown.stopAll(bots, Duration.ofSeconds(5), Duration.ofSeconds(2)));
        assertTrue(calls.toString().startsWith("shutdown shutdown await("), calls.toString());
        assertFalse(calls.toString().contains("now"), calls.toString());
    }

    @Test
    void forcesOnlyTheBotsThatIgnoredTheGracefulRequestAndWaitsAgainBriefly() {
        StringBuilder calls = new StringBuilder();
        FakeBot stubborn = new FakeBot(false, true, calls);
        assertTrue(BotShutdown.stopAll(List.of(new FakeBot(true, true, calls), stubborn), Duration.ofMillis(50), Duration.ofMillis(20)));
        String trace = calls.toString();
        assertEquals(1, trace.split("now ").length - 1, trace);
        assertTrue(stubborn.forced);
    }

    @Test
    void reportsFailureInsteadOfWaitingForeverWhenABotNeverStops() {
        StringBuilder calls = new StringBuilder();
        long started = System.nanoTime();
        assertFalse(BotShutdown.stopAll(List.of(new FakeBot(false, false, calls)), Duration.ofMillis(30), Duration.ofMillis(10)));
        assertTrue(System.nanoTime() - started < Duration.ofSeconds(2).toNanos());
    }
}
