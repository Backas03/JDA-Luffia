package kr.kro.backas.music.lyrics.sync;

import org.jetbrains.annotations.Nullable;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

final class LeadingQuiet {

    static final int FRAME_MS = 100;
    static final double LOUD_PERCENTILE = 0.9;
    static final double QUIET_RATIO = 0.1;
    static final int SUSTAIN_FRAMES = 10;
    static final long MARGIN_MS = 1_000;
    static final long MIN_SKIP_MS = 2_000;

    private LeadingQuiet() {
    }

    static long skipMs(byte[] wav) {
        short[] samples = samples(wav);
        if (samples == null) return 0;
        int rate = rate(wav);
        int hop = rate * FRAME_MS / 1000;
        int frames = samples.length / hop;
        if (frames <= SUSTAIN_FRAMES) return 0;
        double[] rms = new double[frames];
        for (int f = 0; f < frames; f++) {
            double sum = 0;
            for (int i = f * hop; i < (f + 1) * hop; i++) sum += (double) samples[i] * samples[i];
            rms[f] = Math.sqrt(sum / hop);
        }
        double[] sorted = rms.clone();
        Arrays.sort(sorted);
        double threshold = sorted[(int) Math.min(frames - 1, Math.floor(frames * LOUD_PERCENTILE))] * QUIET_RATIO;
        if (threshold <= 0) return 0;
        for (int f = 0; f + SUSTAIN_FRAMES <= frames; f++) {
            if (rms[f] < threshold) continue;
            int loud = 0;
            for (int k = f; k < f + SUSTAIN_FRAMES; k++) {
                if (rms[k] >= threshold) loud++;
            }
            if (loud * 2 < SUSTAIN_FRAMES) continue;
            long skip = (long) f * FRAME_MS - MARGIN_MS;
            return skip >= MIN_SKIP_MS ? skip : 0;
        }
        return 0;
    }

    static byte[] drop(byte[] wav, long ms) {
        short[] samples = samples(wav);
        if (samples == null || ms <= 0) return wav;
        int rate = rate(wav);
        int from = (int) Math.min(samples.length, ms * rate / 1000);
        return PcmCapture.toWav(Arrays.copyOfRange(samples, from, samples.length), samples.length - from, rate);
    }

    private static int rate(byte[] wav) {
        return ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN).getInt(24);
    }

    @Nullable
    private static short[] samples(byte[] wav) {
        if (wav.length < 44) return null;
        ByteBuffer buffer = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN);
        if (buffer.getShort(20) != 1 || buffer.getShort(22) != 1 || buffer.getShort(34) != 16) return null;
        if (buffer.getInt(24) < 1000) return null;
        short[] samples = new short[(wav.length - 44) / 2];
        buffer.position(44);
        buffer.asShortBuffer().get(samples);
        return samples;
    }
}
