package kr.kro.backas.music.llm;

public final class SpeedModel {

    public static final int MAX_LEVEL = 8;
    static final double DEFAULT_SPEED = 40;
    static final double DECAY = 0.6;
    private static final double ALPHA = 0.3;

    private final double[] measured = new double[MAX_LEVEL + 1];
    private final int[] samples = new int[MAX_LEVEL + 1];

    public synchronized void record(int concurrency, double tokensPerSecond) {
        if (!(tokensPerSecond > 0) || Double.isInfinite(tokensPerSecond)) return;
        int level = clamp(concurrency);
        measured[level] = samples[level] == 0 ? tokensPerSecond : measured[level] * (1 - ALPHA) + tokensPerSecond * ALPHA;
        samples[level]++;
    }

    public synchronized double estimate(int concurrency) {
        int level = clamp(concurrency);
        if (samples[level] > 0) return measured[level];
        int lower = level - 1;
        while (lower >= 1 && samples[lower] == 0) lower--;
        int upper = level + 1;
        while (upper <= MAX_LEVEL && samples[upper] == 0) upper++;
        boolean hasLower = lower >= 1;
        boolean hasUpper = upper <= MAX_LEVEL;
        if (hasLower && hasUpper) {
            double weight = (double) (level - lower) / (upper - lower);
            return measured[lower] + (measured[upper] - measured[lower]) * weight;
        }
        if (hasLower) return measured[lower] * Math.pow((double) lower / level, DECAY);
        if (hasUpper) return measured[upper] * Math.pow((double) upper / level, DECAY);
        return DEFAULT_SPEED * Math.pow(1.0 / level, DECAY);
    }

    public synchronized boolean isMeasured(int concurrency) {
        return samples[clamp(concurrency)] > 0;
    }

    public synchronized void restore(int concurrency, double tokensPerSecond) {
        if (concurrency < 1 || concurrency > MAX_LEVEL) return;
        if (!(tokensPerSecond > 0) || Double.isInfinite(tokensPerSecond)) return;
        if (samples[concurrency] > 0) return;
        measured[concurrency] = tokensPerSecond;
        samples[concurrency] = 1;
    }

    public synchronized double[] learned() {
        double[] values = new double[MAX_LEVEL + 1];
        for (int level = 1; level <= MAX_LEVEL; level++) values[level] = samples[level] > 0 ? measured[level] : 0;
        return values;
    }

    private static int clamp(int concurrency) {
        return Math.max(1, Math.min(MAX_LEVEL, concurrency));
    }
}
