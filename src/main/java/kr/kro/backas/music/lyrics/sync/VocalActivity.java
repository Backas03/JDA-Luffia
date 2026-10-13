package kr.kro.backas.music.lyrics.sync;

import org.jetbrains.annotations.Nullable;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

public final class VocalActivity {

    static final int FRAME_MS = 10;
    static final int WINDOW_MS = 32;
    static final double LOW_HZ = 200;
    static final double HIGH_HZ = 4000;
    static final double MIN_PITCH_HZ = 100;
    static final double MAX_PITCH_HZ = 800;
    static final int RISE_FRAMES = 20;
    static final double VOICED_HARMONICITY = 0.15;
    static final double MIN_GLIDE_CENTS = 8;
    static final double MAX_GLIDE_CENTS = 80;
    static final int SINGING_RADIUS_FRAMES = 30;
    static final double SINGING_SHARE = 0.15;
    static final double MIN_ONSET_RISE = 0.06;
    static final double CLEAR_ONSET_DOMINANCE = 1.25;
    static final float PRE_EMPHASIS = 0.95f;
    static final int ATTACK_HISTORY = 3;
    static final double QUIET_SHARE = 0.25;
    static final long SETTLE_MS = 300;
    static final double SETTLE_SHARE = 0.5;

    private final long durationMs;
    private final float[] onset;
    private final float[] attack;
    private final int[] singing;

    private VocalActivity(long durationMs, float[] onset, float[] attack, int[] singing) {
        this.durationMs = durationMs;
        this.onset = onset;
        this.attack = attack;
        this.singing = singing;
    }

    @Nullable
    public static VocalActivity of(byte[] wav) {
        if (wav.length < 44) return null;
        ByteBuffer buffer = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN);
        if (buffer.getShort(20) != 1 || buffer.getShort(22) != 1 || buffer.getShort(34) != 16) return null;
        int rate = buffer.getInt(24);
        if (rate < 2 * HIGH_HZ) return null;
        short[] samples = new short[(wav.length - 44) / 2];
        buffer.position(44);
        buffer.asShortBuffer().get(samples);
        return analyze(samples, rate);
    }

    public long onsetNear(long approxMs, long backMs, long forwardMs) {
        long first = Math.max(RISE_FRAMES, Math.floorDiv(approxMs - backMs, FRAME_MS));
        long last = Math.min(onset.length - RISE_FRAMES, Math.floorDiv(approxMs + forwardMs, FRAME_MS));
        if (first > last) return approxMs;
        int from = (int) first;
        int to = (int) last;
        int best = from;
        for (int f = from; f <= to; f++) {
            if (onset[f] > onset[best]) best = f;
        }
        if (onset[best] < MIN_ONSET_RISE) return approxMs;
        for (int f = from; f <= to; f++) {
            if (Math.abs(f - best) < RISE_FRAMES || !isLocalPeak(f)) continue;
            if (onset[f] * CLEAR_ONSET_DOMINANCE > onset[best]) return approxMs;
        }
        return (long) best * FRAME_MS;
    }

    public double vocalRatio(long fromMs, long toMs) {
        int frames = singing.length - 1;
        int from = (int) Math.max(0, Math.min(frames, Math.floorDiv(fromMs, FRAME_MS)));
        int to = (int) Math.max(0, Math.min(frames, Math.floorDiv(toMs + FRAME_MS - 1, FRAME_MS)));
        if (to <= from) return 0;
        return (double) (singing[to] - singing[from]) / (to - from);
    }

    public long quietFrom(long fromMs, long toMs, long minMs) {
        long latest = toMs - minMs;
        for (long at = Math.max(0, Math.floorDiv(fromMs, FRAME_MS) * FRAME_MS); at <= latest; at += FRAME_MS) {
            if (vocalRatio(at, toMs) > QUIET_SHARE) continue;
            long start = at;
            while (start + FRAME_MS <= latest && vocalRatio(start, start + SETTLE_MS) >= SETTLE_SHARE) start += FRAME_MS;
            return start;
        }
        return -1;
    }

    public long durationMs() {
        return durationMs;
    }

    float[] onsetEnvelope() {
        return onset;
    }

    float[] attackEnvelope() {
        return attack;
    }

    private boolean isLocalPeak(int f) {
        return onset[f] > 0 && onset[f] >= onset[f - 1] && onset[f] >= onset[f + 1];
    }

    static VocalActivity analyze(short[] samples, int rate) {
        int hop = Math.max(1, rate * FRAME_MS / 1000);
        int size = Integer.highestOneBit(Math.max(64, rate * WINDOW_MS / 1000) - 1) << 1;
        int frames = samples.length / hop;
        int low = Math.max(1, (int) Math.round(LOW_HZ * size / rate));
        int high = Math.min(size / 2, (int) Math.round(HIGH_HZ * size / rate));
        int minLag = Math.max(2, (int) (rate / MAX_PITCH_HZ));
        int maxLag = Math.min(size / 2 - 2, (int) Math.ceil(rate / MIN_PITCH_HZ));

        double[] window = new double[size];
        for (int i = 0; i < size; i++) window[i] = 0.5 - 0.5 * Math.cos(2 * Math.PI * i / size);
        double[] windowAcf = new double[maxLag + 2];
        for (int lag = 0; lag < windowAcf.length; lag++) {
            double sum = 0;
            for (int i = 0; i + lag < size; i++) sum += window[i] * window[i + lag];
            windowAcf[lag] = sum;
        }
        for (int lag = windowAcf.length - 1; lag >= 0; lag--) windowAcf[lag] /= windowAcf[0];

        Fft fft = new Fft(size);
        double[] re = new double[size];
        double[] im = new double[size];
        double[] acfA = new double[size];
        double[] acfB = new double[size];
        float[] harmonicity = new float[frames];
        float[] pitchLag = new float[frames];
        double[] pick = new double[2];
        for (int t = 0; t < frames; t += 2) {
            int startA = t * hop + hop / 2 - size / 2;
            int startB = startA + hop;
            for (int i = 0; i < size; i++) {
                int a = startA + i;
                int b = startB + i;
                re[i] = a >= 0 && a < samples.length ? samples[a] / 32768.0 * window[i] : 0;
                im[i] = b >= 0 && b < samples.length ? samples[b] / 32768.0 * window[i] : 0;
            }
            fft.transform(re, im);
            Arrays.fill(acfA, 0);
            Arrays.fill(acfB, 0);
            for (int k = low; k < high; k++) {
                int m = size - k;
                double ar = re[k] + re[m];
                double ai = im[k] - im[m];
                double br = im[k] + im[m];
                double bi = re[m] - re[k];
                double magnitudeA = Math.sqrt(ar * ar + ai * ai);
                double magnitudeB = Math.sqrt(br * br + bi * bi);
                acfA[k] = magnitudeA;
                acfA[m] = magnitudeA;
                acfB[k] = magnitudeB;
                acfB[m] = magnitudeB;
            }
            fft.transform(acfA, acfB);
            strongestPeriod(acfA, windowAcf, minLag, maxLag, pick);
            harmonicity[t] = (float) pick[0];
            pitchLag[t] = (float) pick[1];
            if (t + 1 < frames) {
                strongestPeriod(acfB, windowAcf, minLag, maxLag, pick);
                harmonicity[t + 1] = (float) pick[0];
                pitchLag[t + 1] = (float) pick[1];
            }
        }
        return new VocalActivity((long) samples.length * 1000 / rate, rise(harmonicity), attack(samples, hop, frames), singing(harmonicity, pitchLag));
    }

    private static void strongestPeriod(double[] acf, double[] windowAcf, int minLag, int maxLag, double[] out) {
        out[0] = 0;
        out[1] = 0;
        if (acf[0] <= 0) return;
        double best = 0;
        int at = 0;
        for (int lag = minLag; lag <= maxLag; lag++) {
            if (acf[lag] < acf[lag - 1] || acf[lag] < acf[lag + 1]) continue;
            double value = acf[lag] / windowAcf[lag];
            if (value > best) {
                best = value;
                at = lag;
            }
        }
        if (at == 0) return;
        double before = acf[at - 1] / windowAcf[at - 1];
        double after = acf[at + 1] / windowAcf[at + 1];
        double curvature = before - 2 * best + after;
        out[0] = Math.min(1, best / acf[0]);
        out[1] = curvature == 0 ? at : at + 0.5 * (before - after) / curvature;
    }

    private static float[] attack(short[] samples, int hop, int frames) {
        double[] logEnergy = new double[frames];
        float previous = 0;
        for (int f = 0; f < frames; f++) {
            double energy = 0;
            int base = f * hop;
            for (int i = 0; i < hop; i++) {
                float sample = samples[base + i] / 32768f;
                float emphasised = sample - PRE_EMPHASIS * previous;
                previous = sample;
                energy += emphasised * emphasised;
            }
            logEnergy[f] = Math.log(1e-6 + energy / hop);
        }
        float[] attack = new float[frames];
        for (int f = ATTACK_HISTORY; f < frames; f++) {
            double history = 0;
            for (int h = 1; h <= ATTACK_HISTORY; h++) history += logEnergy[f - h];
            attack[f] = (float) Math.max(0, logEnergy[f] - history / ATTACK_HISTORY);
        }
        return attack;
    }

    private static float[] rise(float[] harmonicity) {
        int frames = harmonicity.length;
        double[] prefix = new double[frames + 1];
        for (int f = 0; f < frames; f++) prefix[f + 1] = prefix[f] + harmonicity[f];
        float[] rise = new float[frames];
        for (int f = RISE_FRAMES; f + RISE_FRAMES <= frames; f++) {
            double after = prefix[f + RISE_FRAMES] - prefix[f];
            double before = prefix[f] - prefix[f - RISE_FRAMES];
            rise[f] = (float) Math.max(0, (after - before) / RISE_FRAMES);
        }
        return rise;
    }

    private static int[] singing(float[] harmonicity, float[] pitchLag) {
        int frames = harmonicity.length;
        int[] gliding = new int[frames + 1];
        for (int f = 0; f < frames; f++) {
            boolean glide = f > 0 && harmonicity[f] >= VOICED_HARMONICITY && harmonicity[f - 1] >= VOICED_HARMONICITY
                    && pitchLag[f] > 0 && pitchLag[f - 1] > 0;
            if (glide) {
                double cents = Math.abs(1200 * Math.log(pitchLag[f] / pitchLag[f - 1]) / Math.log(2));
                glide = cents >= MIN_GLIDE_CENTS && cents < MAX_GLIDE_CENTS;
            }
            gliding[f + 1] = gliding[f] + (glide ? 1 : 0);
        }
        int[] singing = new int[frames + 1];
        for (int f = 0; f < frames; f++) {
            int from = Math.max(0, f - SINGING_RADIUS_FRAMES);
            int to = Math.min(frames, f + SINGING_RADIUS_FRAMES + 1);
            boolean sung = gliding[to] - gliding[from] >= SINGING_SHARE * (to - from);
            singing[f + 1] = singing[f] + (sung ? 1 : 0);
        }
        return singing;
    }

    private static final class Fft {
        private final int size;
        private final int[] reversed;
        private final double[] cos;
        private final double[] sin;

        Fft(int size) {
            this.size = size;
            this.reversed = new int[size];
            int bits = Integer.numberOfTrailingZeros(size);
            for (int i = 0; i < size; i++) reversed[i] = Integer.reverse(i) >>> (32 - bits);
            this.cos = new double[size / 2];
            this.sin = new double[size / 2];
            for (int i = 0; i < size / 2; i++) {
                cos[i] = Math.cos(-2 * Math.PI * i / size);
                sin[i] = Math.sin(-2 * Math.PI * i / size);
            }
        }

        void transform(double[] re, double[] im) {
            for (int i = 0; i < size; i++) {
                int j = reversed[i];
                if (i >= j) continue;
                double swap = re[i];
                re[i] = re[j];
                re[j] = swap;
                swap = im[i];
                im[i] = im[j];
                im[j] = swap;
            }
            for (int length = 2; length <= size; length <<= 1) {
                int half = length >> 1;
                int stride = size / length;
                for (int start = 0; start < size; start += length) {
                    for (int k = 0; k < half; k++) {
                        int a = start + k;
                        int b = a + half;
                        double wr = cos[k * stride];
                        double wi = sin[k * stride];
                        double xr = re[b] * wr - im[b] * wi;
                        double xi = re[b] * wi + im[b] * wr;
                        re[b] = re[a] - xr;
                        im[b] = im[a] - xi;
                        re[a] += xr;
                        im[a] += xi;
                    }
                }
            }
        }
    }
}
