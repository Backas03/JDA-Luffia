package kr.kro.backas.music.lyrics;

import com.github.topi314.lavalyrics.lyrics.AudioLyrics;
import com.github.topi314.lavalyrics.lyrics.BasicAudioLyrics;
import com.sedmelluq.discord.lavaplayer.track.AudioTrackInfo;
import com.sedmelluq.discord.lavaplayer.track.DelegatedAudioTrack;
import com.sedmelluq.discord.lavaplayer.track.playback.LocalAudioTrackExecutor;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SpotifyLyricsTest {

    private static AudioTrackInfo info(String title, String author, long lengthMs, String uri) {
        return new AudioTrackInfo(title, author, lengthMs, "id", false, uri);
    }

    private static DelegatedAudioTrack track(AudioTrackInfo info) {
        return new DelegatedAudioTrack(info) {
            @Override
            public void process(LocalAudioTrackExecutor executor) {
            }
        };
    }

    private static AudioLyrics.Line line(long ms, String text) {
        return new BasicAudioLyrics.BasicLine(Duration.ofMillis(ms), Duration.ZERO, text);
    }

    @Test
    void readsTheTrackIdFromASpotifyLink() {
        assertEquals("7oSfVLm4UAOlpfbTgDfBea", SpotifyLyricsClient.spotifyId(track(info("a", "b", 1000, "https://open.spotify.com/track/7oSfVLm4UAOlpfbTgDfBea?si=x"))));
        assertNull(SpotifyLyricsClient.spotifyId(track(info("a", "b", 1000, "https://www.youtube.com/watch?v=abc"))));
    }

    @Test
    void acceptsASearchHitOnlyWhenTitleAndLengthAgree() {
        AudioTrackInfo video = info("Sample Artist – Night Walk (official video)", "Sample Artist", 202_000, "https://youtu.be/x");
        assertTrue(SpotifyLyricsClient.matches(video, info("Night Walk", "Sample Artist", 205_000, "spotify"), false));
        assertTrue(SpotifyLyricsClient.matches(video, info("Night Walk - Remastered 2020", "Sample Artist", 203_000, "spotify"), false));
        assertFalse(SpotifyLyricsClient.matches(video, info("Night Walk", "Sample Artist", 230_000, "spotify"), false));
        assertFalse(SpotifyLyricsClient.matches(video, info("Morning Run", "Sample Artist", 202_000, "spotify"), false));
        assertTrue(SpotifyLyricsClient.matches(video, info("Completely Different Name", "x", 201_000, "spotify"), true));
    }

    @Test
    void keepsTimingWhenSpotifyHasItAndFallsBackToPlainLyricsOtherwise() {
        Lyrics synced = SpotifyLyricsClient.toLyrics(new BasicAudioLyrics("spotify", "Musixmatch", null,
                List.of(line(1_500, "first line"), line(4_000, "♪"), line(6_200, "second line"))), "Song", "Artist");
        assertEquals(3, synced.synced().size());
        assertEquals(6_200, synced.synced().get(2).timeMs());
        assertEquals("first line\n♪\nsecond line", synced.plain());

        Lyrics plain = SpotifyLyricsClient.toLyrics(new BasicAudioLyrics("spotify", "Musixmatch", null,
                List.of(line(0, "first line"), line(0, "second line"))), "Song", "Artist");
        assertFalse(plain.hasSynced());
        assertEquals("first line\nsecond line", plain.plain());

        assertNull(SpotifyLyricsClient.toLyrics(new BasicAudioLyrics("spotify", "Musixmatch", null, List.of()), "Song", "Artist"));
    }

    @Test
    void prefersSyncedLyricsFromEitherSourceAndKeepsLrcLibOtherwise() {
        Lyrics lrcSynced = new Lyrics("t", "a", "x", List.of(new LyricLine(1000, "x")), false);
        Lyrics lrcPlain = new Lyrics("t", "a", "x", List.of(), false);
        Lyrics spotifySynced = new Lyrics("t", "a", "y", List.of(new LyricLine(1000, "y")), false);
        Lyrics spotifyPlain = new Lyrics("t", "a", "y", List.of(), false);
        assertSame(lrcSynced, LyricsSources.prefer(lrcSynced, spotifySynced));
        assertSame(spotifySynced, LyricsSources.prefer(lrcPlain, spotifySynced));
        assertSame(lrcPlain, LyricsSources.prefer(lrcPlain, spotifyPlain));
        assertSame(spotifyPlain, LyricsSources.prefer(null, spotifyPlain));
        assertNull(LyricsSources.prefer(null, null));
    }
}
