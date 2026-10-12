package kr.kro.backas.music.lyrics.sync;

import kr.kro.backas.music.lyrics.LyricLine;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public final class OnsetRefiner {

    public record Refinement(long offsetMs, double confidence, int lines) {
    }

    public record Analysis(long offsetMs, double zScore, double peakRatio, double dominance, int lines, double[] scores) {
        public boolean isConfident() {
            return lines >= MIN_LINES && zScore >= MIN_Z_SCORE && peakRatio >= MIN_PEAK_RATIO && dominance >= MIN_DOMINANCE;
        }
    }

    static final int FRAME_MS = VocalActivity.FRAME_MS;
    static final long DEFAULT_WINDOW_MS = 600;
    static final long PEAK_TOLERANCE_MS = 30;
    static final double MIN_Z_SCORE = 1.5;
    static final double MIN_PEAK_RATIO = 1.3;
    static final double MIN_DOMINANCE = 1.3;
    static final long RIVAL_DISTANCE_MS = 250;
    static final long ATTACK_EARLIER_MS = 120;
    static final long ATTACK_LATER_MS = 40;
    static final int MIN_LINES = 3;
    static final double PRIOR_SIGMA_MS = 300;

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
        VocalActivity activity = VocalActivity.of(wav);
        return activity == null ? null : analyze(activity, lines, coarseOffsetMs, windowMs);
    }

    @Nullable
    public static Analysis analyze(VocalActivity activity, List<LyricLine> lines, long coarseOffsetMs, long windowMs) {
        float[] envelope = activity.onsetEnvelope();
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
        double[] smoothed = new double[scores.length];
        double[] weighted = new double[scores.length];
        for (int i = 0; i < scores.length; i++) {
            smoothed[i] = (scores[Math.max(0, i - 1)] + scores[i] + scores[Math.min(scores.length - 1, i + 1)]) / 3;
            double delta = (double) (i - steps) * FRAME_MS;
            weighted[i] = smoothed[i] * Math.exp(-(delta * delta) / (2 * PRIOR_SIGMA_MS * PRIOR_SIGMA_MS));
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
        int peak = best;
        while (peak > 0 && smoothed[peak - 1] > smoothed[peak]) peak--;
        while (peak < smoothed.length - 1 && smoothed[peak + 1] > smoothed[peak]) peak++;
        long voiced = coarseOffsetMs + (long) (peak - steps) * FRAME_MS;
        long refined = snapToAttack(activity.attackEnvelope(), anchors, voiced);
        return new Analysis(refined, z, weighted[best] / mean, dominance(smoothed, peak), anchors.size(), weighted);
    }

    private static long snapToAttack(float[] attack, List<Long> anchors, long offsetMs) {
        long first = offsetMs - ATTACK_LATER_MS;
        long last = offsetMs + ATTACK_EARLIER_MS;
        long best = offsetMs;
        double bestScore = 0;
        for (long candidate = first; candidate <= last; candidate += FRAME_MS) {
            double total = 0;
            for (long anchor : anchors) {
                int frame = (int) ((anchor - candidate) / FRAME_MS);
                float peak = 0;
                for (int f = frame - 1; f <= frame + 1; f++) {
                    if (f >= 0 && f < attack.length && attack[f] > peak) peak = attack[f];
                }
                total += peak;
            }
            if (total > bestScore) {
                bestScore = total;
                best = candidate;
            }
        }
        return best == first || best == last ? offsetMs : best;
    }

    private static double dominance(double[] smoothed, int peak) {
        if (peak == 0 || peak == smoothed.length - 1 || smoothed[peak] <= 0) return 0;
        int distance = (int) (RIVAL_DISTANCE_MS / FRAME_MS);
        double rival = 0;
        for (int i = 1; i < smoothed.length - 1; i++) {
            if (Math.abs(i - peak) < distance || smoothed[i] < smoothed[i - 1] || smoothed[i] < smoothed[i + 1]) continue;
            rival = Math.max(rival, smoothed[i]);
        }
        return rival <= 0 ? Double.POSITIVE_INFINITY : smoothed[peak] / rival;
    }
}
