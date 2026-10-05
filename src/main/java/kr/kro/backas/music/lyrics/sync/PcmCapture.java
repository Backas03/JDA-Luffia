package kr.kro.backas.music.lyrics.sync;

import com.sedmelluq.discord.lavaplayer.filter.FloatPcmAudioFilter;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import org.jetbrains.annotations.Nullable;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;

public final class PcmCapture {

    public static final int TARGET_RATE = 16000;
    public static final int DEFAULT_SECONDS = 90;
    public static final int MIN_SECONDS = 20;

    private final AudioTrack track;
    private final int outputRate;
    private final int decimation;
    private final short[] samples;
    private final CompletableFuture<byte[]> wav = new CompletableFuture<>();
    private int count;
    private float sum;
    private int accumulated;

    public PcmCapture(@Nullable AudioTrack track, int sampleRate, int seconds) {
        this.track = track;
        this.decimation = Math.max(1, sampleRate / TARGET_RATE);
        this.outputRate = sampleRate / decimation;
        this.samples = new short[outputRate * seconds];
    }

    @Nullable
    public AudioTrack track() {
        return track;
    }

    public CompletableFuture<byte[]> wav() {
        return wav;
    }

    public synchronized long capturedMs() {
        return count * 1000L / outputRate;
    }

    public synchronized byte[] snapshot() {
        return toWav(samples, count, outputRate);
    }

    public FloatPcmAudioFilter tap(FloatPcmAudioFilter downstream) {
        return new Tap(downstream);
    }

    synchronized void feed(float[][] input, int offset, int length) {
        if (count >= samples.length) return;
        int channels = input.length;
        for (int i = offset; i < offset + length; i++) {
            float mono = 0;
            for (int channel = 0; channel < channels; channel++) mono += input[channel][i];
            sum += mono / channels;
            if (++accumulated < decimation) continue;
            float value = sum / decimation;
            sum = 0;
            accumulated = 0;
            samples[count++] = (short) Math.max(-32768, Math.min(32767, Math.round(value * 32767f)));
            if (count >= samples.length) {
                complete();
                return;
            }
        }
    }

    public synchronized void finish() {
        if (wav.isDone()) return;
        if (count >= outputRate * MIN_SECONDS) complete();
        else wav.complete(null);
    }

    private void complete() {
        wav.complete(toWav(samples, count, outputRate));
    }

    static byte[] toWav(short[] samples, int count, int rate) {
        int dataSize = count * 2;
        ByteBuffer buffer = ByteBuffer.allocate(44 + dataSize).order(ByteOrder.LITTLE_ENDIAN);
        buffer.put("RIFF".getBytes(StandardCharsets.US_ASCII)).putInt(36 + dataSize)
                .put("WAVE".getBytes(StandardCharsets.US_ASCII))
                .put("fmt ".getBytes(StandardCharsets.US_ASCII)).putInt(16)
                .putShort((short) 1).putShort((short) 1).putInt(rate).putInt(rate * 2).putShort((short) 2).putShort((short) 16)
                .put("data".getBytes(StandardCharsets.US_ASCII)).putInt(dataSize);
        for (int i = 0; i < count; i++) buffer.putShort(samples[i]);
        return buffer.array();
    }

    private final class Tap implements FloatPcmAudioFilter {
        private final FloatPcmAudioFilter downstream;

        private Tap(FloatPcmAudioFilter downstream) {
            this.downstream = downstream;
        }

        @Override
        public void process(float[][] input, int offset, int length) throws InterruptedException {
            feed(input, offset, length);
            downstream.process(input, offset, length);
        }

        @Override
        public void seekPerformed(long requestedTime, long providedTime) {
            downstream.seekPerformed(requestedTime, providedTime);
        }

        @Override
        public void flush() throws InterruptedException {
            downstream.flush();
        }

        @Override
        public void close() {
            downstream.close();
        }
    }
}
