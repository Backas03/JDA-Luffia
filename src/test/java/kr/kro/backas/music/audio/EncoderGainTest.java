package kr.kro.backas.music.audio;

import com.sedmelluq.discord.lavaplayer.player.AudioConfiguration;
import net.dv8tion.jda.api.audio.AudioNatives;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.ShortBuffer;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class EncoderGainTest {
    private static final int FRAMES = 100;
    private static final int SETTLED_FRAME = 20;

    @Test
    void followsLavaplayerVolumeCurve() {
        assertEquals(1f, EncoderGain.forVolume(100));
        assertEquals(0f, EncoderGain.forVolume(0));
        assertEquals(0.0791f, EncoderGain.forVolume(10), 0.0001f);
        assertEquals(2.4621f, EncoderGain.forVolume(150), 0.01f);
        assertEquals(3.2828f, EncoderGain.forVolume(200), 0.0001f);
    }

    @Test
    void gainOnlyAppliesInsideTheScope() throws InterruptedException {
        assertEquals(1f, EncoderGain.current());
        EncoderGain.during(0.5f, () -> {
            assertEquals(0.5f, EncoderGain.current());
            EncoderGain.during(0.25f, () -> assertEquals(0.25f, EncoderGain.current()));
            assertEquals(0.5f, EncoderGain.current());
        });
        assertEquals(1f, EncoderGain.current());
    }

    @Test
    void lingeringGainLastsUntilReleased() throws InterruptedException {
        EncoderGain.linger(0.3f);
        assertEquals(0.3f, EncoderGain.current());
        EncoderGain.during(0.5f, () -> assertEquals(0.5f, EncoderGain.current()));
        assertEquals(0.3f, EncoderGain.current());
        EncoderGain.release();
        assertEquals(1f, EncoderGain.current());
    }

    @Test
    void encoderScalesByTheScopedGain() throws InterruptedException {
        assumeTrue(AudioNatives.ensureOpus(), "opus-java 네이티브가 없는 플랫폼");
        MusicOpusFormat format = (MusicOpusFormat) MusicOpusFormat.discord(192);
        short[] source = tone(format, 8000);
        float[] full = roundTrip(format, source, 1f);
        float[] quiet = roundTrip(format, source, EncoderGain.forVolume(10));
        int from = SETTLED_FRAME * format.totalSampleCount();
        double ratio = OpusTestAudio.rms(quiet, from, quiet.length) / OpusTestAudio.rms(full, from, full.length);
        assertEquals(EncoderGain.forVolume(10), ratio, 0.002);
    }

    @Test
    void keepsQuietDetailThatIntegerVolumeDestroys() throws InterruptedException {
        assumeTrue(AudioNatives.ensureOpus(), "opus-java 네이티브가 없는 플랫폼");
        MusicOpusFormat format = (MusicOpusFormat) MusicOpusFormat.discord(192);
        float gain = EncoderGain.forVolume(10);
        short[] source = tone(format, 40);
        short[] truncated = new short[source.length];
        for (int i = 0; i < source.length; i++) truncated[i] = (short) (source[i] * 791 / 10000);

        double integerSnr = snr(format, source, gain, roundTrip(format, truncated, 1f));
        double floatSnr = snr(format, source, gain, roundTrip(format, source, gain));
        assertTrue(floatSnr > integerSnr + 10, "integer " + integerSnr + " dB, float " + floatSnr + " dB");
    }

    private static short[] tone(MusicOpusFormat format, double amplitude) {
        short[] pcm = new short[FRAMES * format.totalSampleCount()];
        for (int i = 0; i < pcm.length / 2; i++) {
            short value = (short) Math.round(amplitude * Math.sin(2 * Math.PI * 1000 * i / format.sampleRate));
            pcm[i * 2] = value;
            pcm[i * 2 + 1] = value;
        }
        return pcm;
    }

    private static float[] roundTrip(MusicOpusFormat format, short[] source, float gain) throws InterruptedException {
        AudioConfiguration configuration = new AudioConfiguration();
        configuration.setOpusEncodingQuality(AudioConfiguration.OPUS_QUALITY_MAX);
        int frameLength = format.totalSampleCount();
        List<byte[]> packets = new ArrayList<>();
        MusicOpusEncoder encoder = (MusicOpusEncoder) format.createEncoder(configuration);
        try {
            ShortBuffer pcm = ByteBuffer.allocateDirect(frameLength * Short.BYTES).order(ByteOrder.nativeOrder()).asShortBuffer();
            for (int frame = 0; frame < FRAMES; frame++) {
                pcm.clear();
                pcm.put(source, frame * frameLength, frameLength);
                pcm.flip();
                EncoderGain.during(gain, () -> packets.add(encoder.encode(pcm)));
            }
        } finally {
            encoder.close();
        }
        return OpusTestAudio.decodePackets(format, packets);
    }

    private static double snr(MusicOpusFormat format, short[] source, float gain, float[] decoded) {
        AudioConfiguration configuration = new AudioConfiguration();
        MusicOpusEncoder probe = (MusicOpusEncoder) format.createEncoder(configuration);
        int delay = probe.lookahead() * format.channelCount;
        probe.close();
        double signal = 0;
        double noise = 0;
        for (int i = SETTLED_FRAME * format.totalSampleCount(); i < decoded.length; i++) {
            double ideal = source[i - delay] / 32768.0 * gain;
            signal += ideal * ideal;
            noise += (decoded[i] - ideal) * (decoded[i] - ideal);
        }
        return 10 * Math.log10(signal / noise);
    }
}
