package kr.kro.backas.music.lyrics.sync;

import kr.kro.backas.music.lyrics.LyricLine;
import org.jetbrains.annotations.Nullable;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public final class OnsetRefiner {

    public record Refinement(long offsetMs, double confidence, int lines) {
    }

    public record Analysis(long offsetMs, double zScore, double peakRatio, int lines, double[] scores) {
        public boolean isConfident() {
            return lines >= MIN_LINES && zScore >= MIN_Z_SCORE && peakRatio >= MIN_PEAK_RATIO;
        }
    }

    static final int FRAME_MS = 10;
    static final long DEFAULT_WINDOW_MS = 600;
    static final long PEAK_TOLERANCE_MS = 30;
    static final double MIN_Z_SCORE = 2.0;
    static final double MIN_PEAK_RATIO = 1.3;
    static final int MIN_LINES = 3;
    static final double PRIOR_SIGMA_MS = 300;
    private static final float PRE_EMPHASIS = 0.95f;
    private static final int FLUX_HISTORY = 3;

    private OnsetRefiner() {
    }

    public static Optional<Refinement> refine(byte[] wav, List<LyricLine> lines, long coarseOffsetMs) {
        return refine(wav, lines, coarseOffsetMs, DEFAULT_WINDOW_MS);
    }

    static Optional<Refinement> refine(byte[] wav, List<LyricLine> lines, long coarseOffsetMs, long windowMs) {
        Analysis analysis = analyze(wav, lines, coarseOffsetMs, windowMs);
        if (analysis == null || !analysis.isConfident()) return Optional.empty();
        return Optional.of(new Refinement(analysis.offsetMs(), analysis.zScore(), analysis.lines()));
    }

    @Nullable
    public static Analysis analyze(byte[] wav, List<LyricLine> lines, long coarseOffsetMs, long windowMs) {
        ByteBuffer buffer = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN);
        if (wav.length < 44 || buffer.getShort(22) != 1 || buffer.getShort(34) != 16) return null;
        int rate = buffer.getInt(24);
        int count = (wav.length - 44) / 2;
        short[] samples = new short[count];
        buffer.position(44);
        buffer.asShortBuffer().get(samples);
        float[] envelope = envelope(samples, rate);
        long durationMs = (long) envelope.length * FRAME_MS;

        List<Long> anchors = new ArrayList<>();
        for (LyricLine line : lines) {
            if (LyricsAligner.normalize(line.text()).isEmpty()) continue;
            long expectedMs = line.timeMs() - coarseOffsetMs;
            if (expectedMs < windowMs + PEAK_TOLERANCE_MS || expectedMs > durationMs - windowMs - PEAK_TOLERANCE_MS) continue;
            anchors.add(line.timeMs());
        }
        if (anchors.isEmpty()) return null;

        int steps = (int) (windowMs / FRAME_MS);
        int tolerance = (int) (PEAK_TOLERANCE_MS / FRAME_MS);
        double[] scores = new double[2 * steps + 1];
        for (int step = -steps; step <= steps; step++) {
            long delta = (long) step * FRAME_MS;
            double total = 0;
            for (long anchor : anchors) {
                int frame = (int) ((anchor - coarseOffsetMs - delta) / FRAME_MS);
                float peak = 0;
                for (int f = frame - tolerance; f <= frame + tolerance; f++) {
                    if (f >= 0 && f < envelope.length && envelope[f] > peak) peak = envelope[f];
                }
                total += peak;
            }
            scores[step + steps] = total;
        }
        double[] weighted = new double[scores.length];
        for (int i = 0; i < scores.length; i++) {
            double smoothed = (scores[Math.max(0, i - 1)] + scores[i] + scores[Math.min(scores.length - 1, i + 1)]) / 3;
            double delta = (double) (i - steps) * FRAME_MS;
            weighted[i] = smoothed * Math.exp(-(delta * delta) / (2 * PRIOR_SIGMA_MS * PRIOR_SIGMA_MS));
        }
        int best = 0;
        double sum = 0;
        for (int i = 0; i < weighted.length; i++) {
            sum += weighted[i];
            if (weighted[i] > weighted[best]) best = i;
        }
        double mean = sum / weighted.length;
        if (mean <= 0) return null;
        double variance = 0;
        for (double score : weighted) variance += (score - mean) * (score - mean);
        double deviation = Math.sqrt(variance / weighted.length);
        double z = deviation == 0 ? 0 : (weighted[best] - mean) / deviation;
        long refined = coarseOffsetMs + (long) (best - steps) * FRAME_MS;
        return new Analysis(refined, z, weighted[best] / mean, anchors.size(), weighted);
    }

    static float[] envelope(short[] samples, int rate) {
        int frame = Math.max(1, rate * FRAME_MS / 1000);
        int frames = samples.length / frame;
        double[] logEnergy = new double[frames];
        float previous = 0;
        for (int f = 0; f < frames; f++) {
            double energy = 0;
            int base = f * frame;
            for (int i = 0; i < frame; i++) {
                float sample = samples[base + i] / 32768f;
                float emphasised = sample - PRE_EMPHASIS * previous;
                previous = sample;
                energy += emphasised * emphasised;
            }
            logEnergy[f] = Math.log(1e-6 + energy / frame);
        }
        float[] flux = new float[frames];
        for (int f = FLUX_HISTORY; f < frames; f++) {
            double history = 0;
            for (int h = 1; h <= FLUX_HISTORY; h++) history += logEnergy[f - h];
            flux[f] = (float) Math.max(0, logEnergy[f] - history / FLUX_HISTORY);
        }
        return flux;
    }
}
