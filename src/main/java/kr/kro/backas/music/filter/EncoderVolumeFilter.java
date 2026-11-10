package kr.kro.backas.music.filter;

import com.sedmelluq.discord.lavaplayer.filter.UniversalPcmAudioFilter;
import kr.kro.backas.music.audio.EncoderGain;

import java.nio.ShortBuffer;
import java.util.function.DoubleSupplier;

public final class EncoderVolumeFilter implements UniversalPcmAudioFilter {
    private final UniversalPcmAudioFilter downstream;
    private final DoubleSupplier gain;

    public EncoderVolumeFilter(UniversalPcmAudioFilter downstream, DoubleSupplier gain) {
        this.downstream = downstream;
        this.gain = gain;
    }

    private float nextGain() {
        return (float) gain.getAsDouble();
    }

    @Override
    public void process(float[][] input, int offset, int length) throws InterruptedException {
        EncoderGain.during(nextGain(), () -> downstream.process(input, offset, length));
    }

    @Override
    public void process(short[] input, int offset, int length) throws InterruptedException {
        EncoderGain.during(nextGain(), () -> downstream.process(input, offset, length));
    }

    @Override
    public void process(ShortBuffer buffer) throws InterruptedException {
        EncoderGain.during(nextGain(), () -> downstream.process(buffer));
    }

    @Override
    public void process(short[][] input, int offset, int length) throws InterruptedException {
        EncoderGain.during(nextGain(), () -> downstream.process(input, offset, length));
    }

    @Override
    public void seekPerformed(long requestedTime, long providedTime) {
    }

    @Override
    public void flush() {
        EncoderGain.linger(nextGain());
    }

    @Override
    public void close() {
        EncoderGain.release();
    }
}
