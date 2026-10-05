package kr.kro.backas.music.lyrics;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class EditLatency {

    public static final long INITIAL_LEAD_MS = 600;
    public static final long MIN_LEAD_MS = 300;
    public static final long MAX_LEAD_MS = 1500;
    public static final long RENDER_MARGIN_MS = 450;
    static final long MAX_SAMPLE_MS = 5000;
    static final double ALPHA = 0.3;
    private static final int MAX_CHANNELS = 2000;

    private static final Map<Long, Double> AVERAGES = new ConcurrentHashMap<>();

    private EditLatency() {
    }

    public static void record(long channelId, long latencyMs) {
        if (latencyMs < 0 || latencyMs > MAX_SAMPLE_MS) return;
        if (AVERAGES.size() >= MAX_CHANNELS && !AVERAGES.containsKey(channelId)) AVERAGES.clear();
        AVERAGES.merge(channelId, (double) latencyMs, (old, sample) -> old + ALPHA * (sample - old));
    }

    public static long leadMs(long channelId) {
        Double average = AVERAGES.get(channelId);
        if (average == null) return INITIAL_LEAD_MS;
        return Math.max(MIN_LEAD_MS, Math.min(MAX_LEAD_MS, Math.round(average) + RENDER_MARGIN_MS));
    }

    static void reset() {
        AVERAGES.clear();
    }
}
