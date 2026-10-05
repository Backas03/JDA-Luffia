package kr.kro.backas.music.lyrics.sync;

import com.sedmelluq.discord.lavaplayer.filter.FloatPcmAudioFilter;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PcmCaptureTest {

    private static final class CountingFilter implements FloatPcmAudioFilter {
        int chunks;

        @Override
        public void process(float[][] input, int offset, int length) {
            chunks++;
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

    private static float[][] chunk(float left, float right) {
        float[][] samples = new float[2][960];
        java.util.Arrays.fill(samples[0], left);
        java.util.Arrays.fill(samples[1], right);
        return samples;
    }

    @Test
    void downmixesDecimatesAndCompletesAWavOnceTheLimitIsReached() throws Exception {
        PcmCapture capture = new PcmCapture(null, 48_000, 1);
        CountingFilter downstream = new CountingFilter();
        FloatPcmAudioFilter tap = capture.tap(downstream);
        for (int i = 0; i < 100; i++) tap.process(chunk(0.5f, -0.5f), 0, 960);
        assertEquals(100, downstream.chunks);
        assertTrue(capture.wav().isDone());
        byte[] wav = capture.wav().get();
        assertNotNull(wav);
        assertEquals(44 + 16_000 * 2, wav.length);
        ByteBuffer header = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN);
        assertEquals("RIFF", new String(wav, 0, 4, StandardCharsets.US_ASCII));
        assertEquals(16_000, header.getInt(24));
        assertEquals(1, header.getShort(22));
        assertEquals(16_000 * 2, header.getInt(40));
        assertEquals(0, header.getShort(44));
        assertEquals(1_000, capture.capturedMs());
    }

    @Test
    void finishingEarlyKeepsAtLeastTwentySecondsOrGivesUp() throws Exception {
        PcmCapture tooShort = new PcmCapture(null, 48_000, 60);
        FloatPcmAudioFilter tap = tooShort.tap(new CountingFilter());
        for (int i = 0; i < 250; i++) tap.process(chunk(0.1f, 0.1f), 0, 960);
        assertFalse(tooShort.wav().isDone());
        tooShort.finish();
        assertNull(tooShort.wav().get());

        PcmCapture enough = new PcmCapture(null, 48_000, 60);
        FloatPcmAudioFilter tap2 = enough.tap(new CountingFilter());
        for (int i = 0; i < 50 * 25; i++) tap2.process(chunk(0.1f, 0.1f), 0, 960);
        enough.finish();
        assertNotNull(enough.wav().get());
        assertEquals(25_000, enough.capturedMs());
    }
}
