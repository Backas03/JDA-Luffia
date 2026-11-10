package kr.kro.backas.music.filter;

import com.sedmelluq.discord.lavaplayer.filter.FloatPcmAudioFilter;
import com.sedmelluq.discord.lavaplayer.filter.equalizer.Equalizer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfiguredEqualizerTest {
    private static final int RATE = 48000;
    private static final double AMPLITUDE = 0.05;

    @Test
    void noPresetCarvesDeepHolesOrBoostsTooHard() {
        for (ConfiguredEqualizer preset : ConfiguredEqualizer.values()) {
            for (double frequency = 30; frequency <= 12_000; frequency *= Math.pow(2, 1 / 3.0)) {
                double db = gainDb(preset, frequency);
                String where = preset.getName() + " @ " + Math.round(frequency) + "Hz = " + Math.round(db * 10) / 10.0 + "dB";
                assertTrue(db >= -6, where);
                assertTrue(db <= 10, where);
            }
        }
    }

    private static double gainDb(ConfiguredEqualizer preset, double frequency) {
        int length = RATE / 2;
        float[][] signal = new float[1][length];
        for (int i = 0; i < length; i++) signal[0][i] = (float) (AMPLITUDE * Math.sin(2 * Math.PI * frequency * i / RATE));
        double[] peak = new double[1];
        Equalizer equalizer = new Equalizer(1, new FloatPcmAudioFilter() {
            @Override
            public void process(float[][] input, int offset, int count) {
                for (int i = offset + count / 2; i < offset + count; i++) peak[0] = Math.max(peak[0], Math.abs(input[0][i]));
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
        });
        preset.applyTo(equalizer);
        try {
            equalizer.process(signal, 0, length);
        } catch (InterruptedException e) {
            throw new IllegalStateException(e);
        }
        return 20 * Math.log10(peak[0] / AMPLITUDE);
    }
}
