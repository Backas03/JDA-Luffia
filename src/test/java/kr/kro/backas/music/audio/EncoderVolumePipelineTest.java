package kr.kro.backas.music.audio;

import com.sedmelluq.discord.lavaplayer.player.AudioConfiguration;
import com.sedmelluq.discord.lavaplayer.player.AudioPlayer;
import com.sedmelluq.discord.lavaplayer.player.DefaultAudioPlayerManager;
import com.sedmelluq.discord.lavaplayer.source.local.LocalAudioSourceManager;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import com.sedmelluq.discord.lavaplayer.track.InternalAudioTrack;
import com.sedmelluq.discord.lavaplayer.track.playback.AudioFrame;
import com.sedmelluq.discord.lavaplayer.track.playback.AudioFrameBuffer;
import com.sedmelluq.discord.lavaplayer.track.playback.LocalAudioTrackExecutor;
import kr.kro.backas.music.filter.EncoderVolumeFilter;
import net.dv8tion.jda.api.audio.AudioNatives;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class EncoderVolumePipelineTest {
    private static final int SAMPLE_RATE = 48000;
    private static final int FRAME_SAMPLES = 960;
    private static final int TOTAL_SAMPLES = 500 * FRAME_SAMPLES + FRAME_SAMPLES / 2;
    private static final double AMPLITUDE = 8000;
    private static final double FULL_SCALE_RMS = AMPLITUDE / 32768 / Math.sqrt(2);
    private static final int CHANGE_AT = 50;

    @Test
    void appliesVolumeInTheEncoderAndRescalesTheBufferOnChange(@TempDir Path directory) throws Exception {
        assumeTrue(AudioNatives.ensureOpus(), "opus-java 네이티브가 없는 플랫폼");
        Path wav = directory.resolve("tone.wav");
        writeTone(wav);

        DefaultAudioPlayerManager manager = new DefaultAudioPlayerManager();
        try {
            AudioConfiguration configuration = manager.getConfiguration();
            configuration.setOpusEncodingQuality(AudioConfiguration.OPUS_QUALITY_MAX);
            configuration.setOutputFormat(MusicOpusFormat.discord(192));
            configuration.setFilterHotSwapEnabled(true);
            manager.registerSourceManager(new LocalAudioSourceManager());
            assertTrue(EncoderVolume.supported(configuration));
            MusicOpusFormat format = (MusicOpusFormat) configuration.getOutputFormat();

            AudioPlayer player = manager.createPlayer();
            EncoderVolume volume = new EncoderVolume(configuration, 10, () -> {
            });
            player.setFilterFactory((track, outputFormat, output) ->
                    List.of(new EncoderVolumeFilter(output, () -> volume.gainFor(bufferOf(player)))));
            player.playTrack((AudioTrack) manager.loadItemSync(wav.toString()));

            List<AudioFrame> frames = new ArrayList<>();
            Thread.sleep(1000);
            pull(player, frames, CHANGE_AT);
            volume.set(50);
            for (int i = 0; i < 6; i++) {
                pull(player, frames, frames.size() + 1);
                Thread.sleep(20);
            }
            Thread.sleep(500);
            pull(player, frames, Integer.MAX_VALUE);

            assertEquals(500, frames.size());
            float[] pcm = OpusTestAudio.decode(format, frames);
            double quiet = FULL_SCALE_RMS * EncoderGain.forVolume(10);
            double louder = FULL_SCALE_RMS * EncoderGain.forVolume(50);
            for (int i = 5; i < 500; i++) {
                double rms = frameRms(pcm, i);
                if (i <= 60) assertEquals(quiet, rms, quiet * 0.03, "frame " + i);
                else if (i >= 85) assertEquals(louder, rms, louder * 0.03, "frame " + i);
                else assertTrue(rms > quiet * 0.97 && rms < louder * 1.03, "frame " + i + " rms " + rms);
            }
            int delay = 312;
            double worst = 0;
            int worstFrame = -1;
            for (int i = 85; i < 500; i++) {
                for (int sample = i * FRAME_SAMPLES; sample < (i + 1) * FRAME_SAMPLES; sample++) {
                    double ideal = AMPLITUDE / 32768 * EncoderGain.forVolume(50)
                            * Math.sin(2 * Math.PI * 1000 * (sample - delay) / SAMPLE_RATE);
                    double error = Math.abs(pcm[sample * 2] - ideal) / (AMPLITUDE / 32768 * EncoderGain.forVolume(50));
                    if (error > worst) {
                        worst = error;
                        worstFrame = i;
                    }
                }
            }
            assertTrue(worst < 0.03, "waveform error " + worst + " at frame " + worstFrame);
        } finally {
            manager.shutdown();
        }
    }

    private static double frameRms(float[] pcm, int frame) {
        return OpusTestAudio.rms(pcm, frame * FRAME_SAMPLES * 2, (frame + 1) * FRAME_SAMPLES * 2);
    }

    private static AudioFrameBuffer bufferOf(AudioPlayer player) {
        if (player.getPlayingTrack() instanceof InternalAudioTrack track
                && track.getActiveExecutor() instanceof LocalAudioTrackExecutor executor) {
            return executor.getAudioBuffer();
        }
        return null;
    }

    private static void pull(AudioPlayer player, List<AudioFrame> frames, int until) throws InterruptedException {
        long deadline = System.nanoTime() + 30_000_000_000L;
        while (frames.size() < until && System.nanoTime() < deadline) {
            AudioFrame frame = player.provide();
            if (frame != null) {
                frames.add(frame);
            } else if (player.getPlayingTrack() == null) {
                return;
            } else {
                Thread.sleep(2);
            }
        }
    }

    private static void writeTone(Path path) throws IOException {
        int dataLength = TOTAL_SAMPLES * 4;
        ByteBuffer wav = ByteBuffer.allocate(44 + dataLength).order(ByteOrder.LITTLE_ENDIAN);
        wav.put("RIFF".getBytes()).putInt(36 + dataLength).put("WAVE".getBytes());
        wav.put("fmt ".getBytes()).putInt(16).putShort((short) 1).putShort((short) 2)
                .putInt(SAMPLE_RATE).putInt(SAMPLE_RATE * 4).putShort((short) 4).putShort((short) 16);
        wav.put("data".getBytes()).putInt(dataLength);
        for (int i = 0; i < TOTAL_SAMPLES; i++) {
            short value = (short) Math.round(AMPLITUDE * Math.sin(2 * Math.PI * 1000 * i / SAMPLE_RATE));
            wav.putShort(value).putShort(value);
        }
        Files.write(path, wav.array());
    }
}
