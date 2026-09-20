package kr.kro.backas.music.filter;

import com.sedmelluq.discord.lavaplayer.filter.FloatPcmAudioFilter;

public class VocalEchoFilter implements FloatPcmAudioFilter {

    private final FloatPcmAudioFilter downstream;
    private final int channelCount;
    private final EchoConverter centerEcho;
    private final float centerGain;
    private float[] center = new float[0];
    private float[] side = new float[0];

    public VocalEchoFilter(FloatPcmAudioFilter downstream,
                           int sampleRate,
                           int channelCount,
                           float echoSeconds,
                           float decay,
                           float centerGain) {
        this.downstream = downstream;
        this.channelCount = channelCount;
        this.centerEcho = new EchoConverter(sampleRate, echoSeconds, decay);
        this.centerGain = centerGain;
    }

    @Override
    public void process(float[][] input, int offset, int length) throws InterruptedException {
        if (channelCount >= 2) {
            processStereo(input[0], input[1], offset, length);
        } else {
            centerEcho.process(input[0], offset, length);
        }
        downstream.process(input, offset, length);
    }

    private void processStereo(float[] left, float[] right, int offset, int length) {
        if (center.length < length) {
            center = new float[length];
            side = new float[length];
        }
        for (int i = 0; i < length; i++) {
            float l = left[offset + i];
            float r = right[offset + i];
            center[i] = (l + r) * 0.5f;
            side[i] = (l - r) * 0.5f;
        }
        centerEcho.process(center, 0, length);
        for (int i = 0; i < length; i++) {
            float c = center[i] * centerGain;
            left[offset + i] = clamp(c + side[i]);
            right[offset + i] = clamp(c - side[i]);
        }
    }

    private static float clamp(float value) {
        if (value > 1f) return 1f;
        if (value < -1f) return -1f;
        return value;
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
