package kr.kro.backas.music.audio;

import com.sedmelluq.discord.lavaplayer.format.AudioDataFormat;
import com.sedmelluq.discord.lavaplayer.format.OpusAudioDataFormat;
import com.sedmelluq.discord.lavaplayer.format.StandardAudioDataFormats;
import com.sedmelluq.discord.lavaplayer.format.transcoder.AudioChunkEncoder;
import com.sedmelluq.discord.lavaplayer.player.AudioConfiguration;
import net.dv8tion.jda.api.audio.AudioNatives;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.ShortBuffer;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class MusicOpusFormatTest {

    @Test
    void zeroBitrateKeepsLavaplayerDefault() {
        assertSame(StandardAudioDataFormats.DISCORD_OPUS, MusicOpusFormat.discord(0));
    }

    @Test
    void bitrateIsClampedToWhatOpusAccepts() {
        assertEquals(MusicOpusFormat.MAX_BITRATE_KBPS * 1000, ((MusicOpusFormat) MusicOpusFormat.discord(9999)).bitrate());
        assertEquals(MusicOpusFormat.MIN_BITRATE_KBPS * 1000, ((MusicOpusFormat) MusicOpusFormat.discord(1)).bitrate());
    }

    @Test
    void equalsSourceOpusSoPassthroughStillWorks() {
        AudioDataFormat format = MusicOpusFormat.discord(192);
        // lavaplayer 는 outputFormat.equals(inputFormat) 이 참이고 볼륨 100·필터 없음이면 원본 패킷을 그대로 보낸다.
        assertTrue(format.equals(new OpusAudioDataFormat(2, 48000, 960)));
        assertTrue(format.equals(StandardAudioDataFormats.DISCORD_OPUS));
        assertEquals(StandardAudioDataFormats.DISCORD_OPUS.hashCode(), format.hashCode());
        assertNotEquals(format, new OpusAudioDataFormat(1, 48000, 960));
        assertNotEquals(format, StandardAudioDataFormats.DISCORD_PCM_S16_BE);
    }

    @Test
    void encodesAtTheConfiguredBitrate() {
        assumeTrue(AudioNatives.ensureOpus(), "opus-java 네이티브가 없는 플랫폼");
        MusicOpusFormat format = (MusicOpusFormat) MusicOpusFormat.discord(192);
        AudioConfiguration configuration = new AudioConfiguration();
        configuration.setOpusEncodingQuality(AudioConfiguration.OPUS_QUALITY_MAX);
        AudioChunkEncoder encoder = format.createEncoder(configuration);
        assertInstanceOf(MusicOpusEncoder.class, encoder);
        try {
            assertEquals(192_000, ((MusicOpusEncoder) encoder).bitrate());
            Random random = new Random(1);
            ShortBuffer pcm = ByteBuffer.allocateDirect(format.totalSampleCount() * 2).order(ByteOrder.nativeOrder()).asShortBuffer();
            ByteBuffer out = ByteBuffer.allocate(format.maximumChunkSize());
            long bytes = 0;
            int frames = 250;
            for (int frame = 0; frame < frames; frame++) {
                pcm.clear();
                for (int i = 0; i < format.totalSampleCount(); i++) pcm.put((short) (random.nextGaussian() * 6000));
                pcm.flip();
                encoder.encode(pcm, out);
                assertEquals(0, out.position());
                bytes += out.remaining();
                assertTrue(out.remaining() <= format.maximumChunkSize());
            }
            double kbps = bytes * 8.0 / (frames * 0.02) / 1000;
            assertTrue(kbps > 150, "noise should use most of the 192kbps budget, got " + kbps);
        } finally {
            encoder.close();
        }
    }
}
