package kr.kro.backas.music.motion;

import com.sedmelluq.discord.lavaplayer.player.AudioPlayerManager;
import com.sedmelluq.discord.lavaplayer.source.AudioSourceManager;
import com.sedmelluq.discord.lavaplayer.track.AudioItem;
import com.sedmelluq.discord.lavaplayer.track.AudioReference;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import com.sedmelluq.discord.lavaplayer.track.AudioTrackInfo;
import com.sedmelluq.discord.lavaplayer.track.BaseAudioTrack;
import com.sedmelluq.discord.lavaplayer.track.playback.LocalAudioTrackExecutor;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;

import java.io.DataInput;
import java.io.DataOutput;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class MotionBannersTest {

    private static final class FakeTrack extends BaseAudioTrack {
        @Nullable
        private final AudioSourceManager source;

        FakeTrack(String identifier, String uri, @Nullable AudioSourceManager source) {
            super(new AudioTrackInfo("t", "a", 200_000, identifier, false, uri));
            this.source = source;
        }

        @Override
        public void process(LocalAudioTrackExecutor executor) {
        }

        @Override
        public AudioSourceManager getSourceManager() {
            return source;
        }
    }

    private static final class NamedSource implements AudioSourceManager {
        private final String name;

        NamedSource(String name) {
            this.name = name;
        }

        @Override
        public String getSourceName() {
            return name;
        }

        @Override
        public AudioItem loadItem(AudioPlayerManager manager, AudioReference reference) {
            return null;
        }

        @Override
        public boolean isTrackEncodable(AudioTrack track) {
            return false;
        }

        @Override
        public void encodeTrack(AudioTrack track, DataOutput output) {
        }

        @Override
        public AudioTrack decodeTrack(AudioTrackInfo trackInfo, DataInput input) {
            return null;
        }

        @Override
        public void shutdown() {
        }
    }

    @Test
    void spotifyTracksAreKeyedByTheirTrackId() {
        FakeTrack track = new FakeTrack("anything", "https://open.spotify.com/intl-ko/track/4PTG3Z6ehGkBFwjybzWkR8?si=1", null);
        assertEquals("4PTG3Z6ehGkBFwjybzWkR8", MotionBanners.spotifyId(track));
        assertEquals("spotify-4PTG3Z6ehGkBFwjybzWkR8", MotionBanners.key(track));
    }

    @Test
    void youtubeTracksAreKeyedByTheirVideoId() {
        FakeTrack track = new FakeTrack("9bZkp7q19f0", "https://www.youtube.com/watch?v=9bZkp7q19f0", new NamedSource("youtube"));
        assertNull(MotionBanners.spotifyId(track));
        assertEquals("youtube-9bZkp7q19f0", MotionBanners.key(track));
    }

    @Test
    void otherSourcesGetNoMotionBanner() {
        assertNull(MotionBanners.key(new FakeTrack("https://example.invalid/a.mp3", "https://example.invalid/a.mp3", null)));
        assertNull(MotionBanners.key(new FakeTrack("9bZkp7q19f0", "https://example.invalid/9bZkp7q19f0", new NamedSource("http"))));
    }

    @Test
    void aFailureIsLoggedWithWhatCausedIt() {
        Exception failure = new java.io.IOException("spotify web player token failed",
                new RuntimeException("Account token refreshing failed", new IllegalStateException("401")));
        assertEquals("java.io.IOException: spotify web player token failed <- java.lang.RuntimeException: Account token refreshing failed"
                + " <- java.lang.IllegalStateException: 401", MotionBanners.describe(failure));
    }

    @Test
    void theEarlierAttemptThatAlsoFailedIsNotLost() {
        Exception failure = new java.io.IOException("spotify web player token failed", new RuntimeException("account"));
        failure.addSuppressed(new RuntimeException("anonymous", new java.net.ConnectException("refused")));
        assertEquals("java.io.IOException: spotify web player token failed <- java.lang.RuntimeException: account"
                + " (before that: java.lang.RuntimeException: anonymous <- java.net.ConnectException: refused)", MotionBanners.describe(failure));
    }

    @Test
    void theClipLengthStaysWithinSaneBounds() {
        assertEquals(6_000, MotionBanners.clipMs(6));
        assertEquals(2_000, MotionBanners.clipMs(0));
        assertEquals(10_000, MotionBanners.clipMs(60));
    }

    @Test
    void scenesNeverGetShorterThanAGlance() {
        assertEquals(3, MotionBanners.scenes(3, 9_000));
        assertEquals(1, MotionBanners.scenes(0, 9_000));
        assertEquals(5, MotionBanners.scenes(20, 10_000));
        assertEquals(2, MotionBanners.scenes(3, 3_000));
    }
}
