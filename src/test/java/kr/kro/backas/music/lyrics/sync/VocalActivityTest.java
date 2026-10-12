package kr.kro.backas.music.lyrics.sync;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VocalActivityTest {

    static final int RATE = 16_000;

    static short[] silence(long seconds, long seed) {
        Random random = new Random(seed);
        short[] samples = new short[(int) (RATE * seconds)];
        for (int i = 0; i < samples.length; i++) samples[i] = (short) (random.nextGaussian() * 100);
        return samples;
    }

    static void drums(short[] samples, long fromMs, long toMs, long beatMs, long seed) {
        Random random = new Random(seed);
        for (long at = fromMs; at < toMs; at += beatMs) {
            int start = (int) (at * RATE / 1000);
            for (int i = 0; i < RATE / 10 && start + i < samples.length; i++) {
                double decay = Math.exp(-i / (RATE * 0.03));
                double kick = Math.sin(2 * Math.PI * 60 * i / RATE) * 9_000;
                add(samples, start + i, (random.nextGaussian() * 6_000 + kick) * decay);
            }
        }
    }

    static void sing(short[] samples, long fromMs, long lengthMs, double pitchHz, double vibratoCents) {
        int start = (int) (fromMs * RATE / 1000);
        int length = (int) (lengthMs * RATE / 1000);
        int ramp = RATE / 50;
        double phase = 0;
        for (int i = 0; i < length && start + i < samples.length; i++) {
            double t = (double) i / RATE;
            double frequency = pitchHz * Math.pow(2, vibratoCents / 1200 * Math.sin(2 * Math.PI * 5.5 * t));
            phase += 2 * Math.PI * frequency / RATE;
            double value = 0;
            for (int harmonic = 1; harmonic <= 8; harmonic++) value += Math.sin(harmonic * phase) / harmonic;
            double envelope = Math.min(1, Math.min((double) i / ramp, (double) (length - i) / ramp));
            add(samples, start + i, value * 5_000 * envelope);
        }
    }

    static void add(short[] samples, int index, double value) {
        samples[index] = (short) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, samples[index] + value));
    }

    static byte[] wav(short[] samples) {
        return PcmCapture.toWav(samples, samples.length, RATE);
    }

    @Test
    void readsOnlySixteenBitMonoPcm() {
        byte[] valid = wav(silence(3, 1));
        VocalActivity activity = VocalActivity.of(valid);
        assertNotNull(activity);
        assertEquals(3_000, activity.durationMs());

        assertNull(VocalActivity.of(new byte[20]));
        byte[] eightBit = valid.clone();
        ByteBuffer.wrap(eightBit).order(ByteOrder.LITTLE_ENDIAN).putShort(34, (short) 8);
        assertNull(VocalActivity.of(eightBit));
        byte[] stereo = valid.clone();
        ByteBuffer.wrap(stereo).order(ByteOrder.LITTLE_ENDIAN).putShort(22, (short) 2);
        assertNull(VocalActivity.of(stereo));
        byte[] floating = valid.clone();
        ByteBuffer.wrap(floating).order(ByteOrder.LITTLE_ENDIAN).putShort(20, (short) 3);
        assertNull(VocalActivity.of(floating));
    }

    @Test
    void findsWhereTheSingingStartsRatherThanTheNearestDrumHit() {
        short[] samples = silence(12, 2);
        drums(samples, 0, 12_000, 500, 3);
        sing(samples, 5_230, 1_800, 220, 60);
        VocalActivity activity = VocalActivity.of(wav(samples));
        assertNotNull(activity);
        long onset = activity.onsetNear(5_000, 600, 600);
        assertTrue(Math.abs(onset - 5_230) <= 40, String.valueOf(onset));
        long fromLate = activity.onsetNear(5_500, 600, 300);
        assertTrue(Math.abs(fromLate - 5_230) <= 40, String.valueOf(fromLate));
    }

    @Test
    void keepsTheGuessWhenOnlyDrumsPlay() {
        short[] samples = silence(12, 4);
        drums(samples, 0, 12_000, 500, 5);
        VocalActivity activity = VocalActivity.of(wav(samples));
        assertNotNull(activity);
        assertEquals(5_100, activity.onsetNear(5_100, 600, 600));
        assertEquals(400, activity.onsetNear(400, 600, 600));
    }

    @Test
    void vocalRatioSeparatesAWobblingVoiceFromDrumsAndSteadyTones() {
        short[] samples = silence(20, 6);
        drums(samples, 0, 20_000, 500, 7);
        sing(samples, 2_000, 5_000, 260, 60);
        sing(samples, 12_000, 5_000, 260, 0);
        VocalActivity activity = VocalActivity.of(wav(samples));
        assertNotNull(activity);
        double voice = activity.vocalRatio(2_500, 6_500);
        double drumsOnly = activity.vocalRatio(8_000, 11_500);
        double steadyTone = activity.vocalRatio(12_500, 16_500);
        assertTrue(voice > 0.8, String.valueOf(voice));
        assertTrue(drumsOnly < 0.1, String.valueOf(drumsOnly));
        assertTrue(steadyTone < 0.2, String.valueOf(steadyTone));
        assertEquals(0, activity.vocalRatio(6_000, 6_000));
        assertEquals(0, activity.vocalRatio(30_000, 40_000));
    }
}
