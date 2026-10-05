package kr.kro.backas.music.lyrics.sync;

import com.sedmelluq.discord.lavaplayer.format.StandardAudioDataFormats;
import com.sedmelluq.discord.lavaplayer.player.AudioLoadResultHandler;
import com.sedmelluq.discord.lavaplayer.player.DefaultAudioPlayerManager;
import com.sedmelluq.discord.lavaplayer.source.local.LocalAudioSourceManager;
import com.sedmelluq.discord.lavaplayer.tools.FriendlyException;
import com.sedmelluq.discord.lavaplayer.track.AudioPlaylist;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScratchCaptureTest {

    @TempDir
    Path tempDir;

    private static AudioTrack load(DefaultAudioPlayerManager manager, String identifier) throws Exception {
        CompletableFuture<AudioTrack> loaded = new CompletableFuture<>();
        manager.loadItem(identifier, new AudioLoadResultHandler() {
            @Override
            public void trackLoaded(AudioTrack track) {
                loaded.complete(track);
            }

            @Override
            public void playlistLoaded(AudioPlaylist playlist) {
                loaded.complete(playlist.getTracks().get(0));
            }

            @Override
            public void noMatches() {
                loaded.completeExceptionally(new IllegalStateException("no matches"));
            }

            @Override
            public void loadFailed(FriendlyException exception) {
                loaded.completeExceptionally(exception);
            }
        });
        return loaded.get(20, TimeUnit.SECONDS);
    }

    @Test
    void decodesTheStartOfATrackFasterThanRealTime() throws Exception {
        int rate = 48_000;
        short[] samples = new short[rate * 25];
        for (int i = 0; i < samples.length; i++) samples[i] = (short) (Math.sin(i * 2 * Math.PI * 440 / rate) * 12_000);
        Path file = tempDir.resolve("tone.wav");
        Files.write(file, PcmCapture.toWav(samples, samples.length, rate));

        DefaultAudioPlayerManager manager = new DefaultAudioPlayerManager();
        try {
            manager.getConfiguration().setOutputFormat(StandardAudioDataFormats.COMMON_PCM_S16_LE);
            manager.registerSourceManager(new LocalAudioSourceManager());
            AudioTrack track = load(manager, file.toString());

            long started = System.currentTimeMillis();
            ScratchCapture.Captured whole = ScratchCapture.capture(manager, track, 60).get(30, TimeUnit.SECONDS);
            long elapsed = System.currentTimeMillis() - started;
            assertNotNull(whole);
            assertTrue(Math.abs(whole.capturedMs() - 25_000) < 300, String.valueOf(whole.capturedMs()));
            assertTrue(elapsed < 15_000, elapsed + "ms");

            ScratchCapture.Captured head = ScratchCapture.capture(manager, track, 5).get(30, TimeUnit.SECONDS);
            assertNotNull(head);
            assertTrue(Math.abs(head.capturedMs() - 5_000) < 100, String.valueOf(head.capturedMs()));
            assertTrue(head.wav().length < whole.wav().length);
        } finally {
            manager.shutdown();
        }
    }
}
