package kr.kro.backas.music.filter;

import com.sedmelluq.discord.lavaplayer.filter.FloatPcmAudioFilter;
import com.sedmelluq.discord.lavaplayer.filter.equalizer.Equalizer;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HeadroomLimiterTest {
    private static final int RATE = 48000;

    private static final class Collector implements FloatPcmAudioFilter {
        final List<float[]> left = new ArrayList<>();
        final List<float[]> right = new ArrayList<>();

        @Override
        public void process(float[][] input, int offset, int length) {
            float[] l = new float[length];
            float[] r = new float[length];
            System.arraycopy(input[0], offset, l, 0, length);
            System.arraycopy(input[1], offset, r, 0, length);
            left.add(l);
            right.add(r);
        }

        float[] channel(List<float[]> chunks) {
            int total = 0;
            for (float[] chunk : chunks) total += chunk.length;
            float[] all = new float[total];
            int at = 0;
            for (float[] chunk : chunks) {
                System.arraycopy(chunk, 0, all, at, chunk.length);
                at += chunk.length;
            }
            return all;
        }

        @Override
        public void seekPerformed(long requestedTime, long providedTime) {
        }

        @Override
        public void flush() {
        }

        @Override
        public void close() {
        }
    }

    private static float[][] sine(double frequency, double amplitude, int samples, int start) {
        float[][] pcm = new float[2][samples];
        for (int i = 0; i < samples; i++) {
            float value = (float) (amplitude * Math.sin(2 * Math.PI * frequency * (start + i) / RATE));
            pcm[0][i] = value;
            pcm[1][i] = value;
        }
        return pcm;
    }

    private static void feed(FloatPcmAudioFilter head, double frequency, double amplitude, int seconds) throws InterruptedException {
        for (int start = 0; start < RATE * seconds; start += 960) {
            head.process(sine(frequency, amplitude, 960, start), 0, 960);
        }
    }

    @Test
    void quietAudioComesBackAtTheSameLevelOnlyDelayed() throws InterruptedException {
        Collector sink = new Collector();
        HeadroomLimiter limiter = new HeadroomLimiter(sink, 2, RATE);
        FloatPcmAudioFilter head = new HeadroomLimiter.Attenuator(limiter);
        float[][] original = sine(440, 0.5, 4800, 0);
        float[][] copy = {original[0].clone(), original[1].clone()};
        head.process(copy, 0, 4800);
        limiter.flush();

        float[] out = sink.channel(sink.left);
        int delay = limiter.latencySamples();
        assertEquals(4800 + delay, out.length);
        for (int i = 0; i < 4800; i++) {
            assertEquals(original[0][i], out[i + delay], 1e-6f, "sample " + i);
        }
    }

    @Test
    void boostedBassNeverExceedsTheCeiling() throws InterruptedException {
        Collector sink = new Collector();
        HeadroomLimiter limiter = new HeadroomLimiter(sink, 2, RATE);
        Equalizer equalizer = new Equalizer(2, limiter);
        ConfiguredEqualizer.SUPER_BASS_BOOST.applyTo(equalizer);
        FloatPcmAudioFilter head = new HeadroomLimiter.Attenuator(equalizer);
        feed(head, 50, 0.95, 2);

        float peak = 0f;
        for (float value : sink.channel(sink.left)) peak = Math.max(peak, Math.abs(value));
        assertTrue(peak <= HeadroomLimiter.CEILING, "peak " + peak);
        assertTrue(peak > HeadroomLimiter.CEILING * 0.9f, "limiter should keep the level up, peak " + peak);
    }

    @Test
    void boostedBassUsedToHardClipWithoutHeadroom() throws InterruptedException {
        Collector sink = new Collector();
        Equalizer equalizer = new Equalizer(2, sink);
        ConfiguredEqualizer.SUPER_BASS_BOOST.applyTo(equalizer);
        feed(equalizer, 50, 0.95, 2);

        int clipped = 0;
        for (float value : sink.channel(sink.left)) if (Math.abs(value) >= 0.99999f) clipped++;
        assertTrue(clipped > 1000, "lavaplayer equalizer clamps internally, clipped " + clipped);
    }

    @Test
    void limitingKeepsTheWaveformCloseToAScaledCopy() throws InterruptedException {
        Collector sink = new Collector();
        HeadroomLimiter limiter = new HeadroomLimiter(sink, 2, RATE);
        FloatPcmAudioFilter head = new HeadroomLimiter.Attenuator(limiter);
        feed(head, 100, 1.6, 2);

        float[] out = sink.channel(sink.left);
        int delay = limiter.latencySamples();
        double error = 0;
        double energy = 0;
        for (int i = RATE; i < RATE * 2 - delay; i++) {
            double expected = HeadroomLimiter.CEILING * Math.sin(2 * Math.PI * 100 * i / RATE);
            error += Math.pow(out[i + delay] - expected, 2);
            energy += expected * expected;
        }
        assertTrue(error / energy < 1e-3, "steady overload should settle into clean gain reduction, relative error " + error / energy);
    }

    @Test
    void seekClearsTheDelayLine() throws InterruptedException {
        Collector sink = new Collector();
        HeadroomLimiter limiter = new HeadroomLimiter(sink, 2, RATE);
        limiter.process(sine(440, 0.2, 960, 0), 0, 960);
        limiter.seekPerformed(1000, 1000);
        limiter.process(new float[2][960], 0, 960);

        float[] out = sink.channel(sink.left);
        for (int i = 960; i < out.length; i++) assertEquals(0f, out[i]);
    }
}
