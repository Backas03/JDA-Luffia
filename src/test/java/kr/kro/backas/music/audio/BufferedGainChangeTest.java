package kr.kro.backas.music.audio;

import com.sedmelluq.discord.lavaplayer.player.AudioConfiguration;
import com.sedmelluq.discord.lavaplayer.track.playback.AllocatingAudioFrameBuffer;
import com.sedmelluq.discord.lavaplayer.track.playback.AudioFrame;
import com.sedmelluq.discord.lavaplayer.track.playback.ImmutableAudioFrame;
import net.dv8tion.jda.api.audio.AudioNatives;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.ShortBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class BufferedGainChangeTest {
    private static final float RATIO = 0.25f;

    @Test
    void rescalesBufferedFramesAfterTheHeldOnes() throws InterruptedException {
        assumeTrue(AudioNatives.ensureOpus(), "opus-java 네이티브가 없는 플랫폼");
        MusicOpusFormat format = (MusicOpusFormat) MusicOpusFormat.discord(192);
        AudioConfiguration configuration = new AudioConfiguration();
        configuration.setOpusEncodingQuality(AudioConfiguration.OPUS_QUALITY_MAX);
        int frames = 100;
        AllocatingAudioFrameBuffer buffer = new AllocatingAudioFrameBuffer(5000, format, new AtomicBoolean());
        List<AudioFrame> originals = fill(buffer, format, configuration, frames);

        int replaced = format.rescaleBuffered(buffer, configuration, 1f, RATIO);
        assertEquals(frames - BufferedGainChange.HOLD_FRAMES, replaced);

        List<AudioFrame> after = drain(buffer);
        assertEquals(frames, after.size());
        float[] before = OpusTestAudio.decode(format, originals);
        float[] rescaled = OpusTestAudio.decode(format, after);
        int frameLength = format.totalSampleCount();
        for (int i = 0; i < frames; i++) {
            assertEquals(originals.get(i).getTimecode(), after.get(i).getTimecode());
            if (i < BufferedGainChange.HOLD_FRAMES) {
                assertSame(originals.get(i), after.get(i));
                continue;
            }
            assertNotSame(originals.get(i), after.get(i));
            if (i < BufferedGainChange.HOLD_FRAMES + BufferedGainChange.RAMP_FRAMES + 1) continue;
            double ratio = OpusTestAudio.rms(rescaled, i * frameLength, (i + 1) * frameLength)
                    / OpusTestAudio.rms(before, i * frameLength, (i + 1) * frameLength);
            assertEquals(RATIO, ratio, 0.01, "frame " + i);
        }
        int junction = BufferedGainChange.HOLD_FRAMES * frameLength;
        double seam = OpusTestAudio.rms(rescaled, junction - frameLength, junction + frameLength)
                / OpusTestAudio.rms(before, junction - frameLength, junction + frameLength);
        assertTrue(seam > 0.85 && seam < 1.05, "seam level ratio " + seam);
    }

    @Test
    void leavesShortBuffersAlone() throws InterruptedException {
        assumeTrue(AudioNatives.ensureOpus(), "opus-java 네이티브가 없는 플랫폼");
        MusicOpusFormat format = (MusicOpusFormat) MusicOpusFormat.discord(192);
        AudioConfiguration configuration = new AudioConfiguration();
        AllocatingAudioFrameBuffer buffer = new AllocatingAudioFrameBuffer(5000, format, new AtomicBoolean());
        List<AudioFrame> originals = fill(buffer, format, configuration, BufferedGainChange.HOLD_FRAMES);
        assertEquals(0, format.rescaleBuffered(buffer, configuration, 1f, RATIO));
        List<AudioFrame> after = drain(buffer);
        assertEquals(originals.size(), after.size());
        for (int i = 0; i < after.size(); i++) assertSame(originals.get(i), after.get(i));
    }

    private static List<AudioFrame> fill(AllocatingAudioFrameBuffer buffer, MusicOpusFormat format,
                                         AudioConfiguration configuration, int frames) throws InterruptedException {
        List<AudioFrame> added = new ArrayList<>();
        int frameLength = format.totalSampleCount();
        MusicOpusEncoder encoder = (MusicOpusEncoder) format.createEncoder(configuration);
        try {
            ShortBuffer pcm = ByteBuffer.allocateDirect(frameLength * Short.BYTES).order(ByteOrder.nativeOrder()).asShortBuffer();
            for (int frame = 0; frame < frames; frame++) {
                pcm.clear();
                for (int i = 0; i < format.chunkSampleCount; i++) {
                    long sample = (long) frame * format.chunkSampleCount + i;
                    short value = (short) Math.round(8000 * Math.sin(2 * Math.PI * 440 * sample / format.sampleRate)
                            + 3000 * Math.sin(2 * Math.PI * 3100 * sample / format.sampleRate));
                    pcm.put(value).put(value);
                }
                pcm.flip();
                AudioFrame audioFrame = new ImmutableAudioFrame(frame * 20L, encoder.encode(pcm), 100, format);
                buffer.consume(audioFrame);
                added.add(audioFrame);
            }
        } finally {
            encoder.close();
        }
        return added;
    }

    private static List<AudioFrame> drain(AllocatingAudioFrameBuffer buffer) {
        List<AudioFrame> frames = new ArrayList<>();
        for (AudioFrame frame = buffer.provide(); frame != null; frame = buffer.provide()) frames.add(frame);
        return frames;
    }
}
