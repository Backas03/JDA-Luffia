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
        assertTrue(SpotifyLyricsClient.matches(video, info("Night Walk", "Sample Artist", 205_000, "spotify"), false, 0));
        assertTrue(SpotifyLyricsClient.matches(video, info("Night Walk - Remastered 2020", "Sample Artist", 203_000, "spotify"), false, 0));
        assertFalse(SpotifyLyricsClient.matches(video, info("Night Walk", "Sample Artist", 230_000, "spotify"), false, 0));
        assertFalse(SpotifyLyricsClient.matches(video, info("Morning Run", "Other Band", 202_000, "spotify"), false, 0));
        assertTrue(SpotifyLyricsClient.matches(video, info("Completely Different Name", "x", 201_000, "spotify"), true, 0));
    }

    @Test
    void acceptsATranslatedTitleFromTheSameArtistWhenTheLengthIsAlmostTheSame() {
        AudioTrackInfo video = info("Sample Artist - Telepathy feat. Singer", "Sample Artist", 138_000, "https://youtu.be/x");
        assertTrue(SpotifyLyricsClient.matches(video, info("テレパシ", "Sample Artist, Singer", 137_000, "spotify"), false, 0));
        assertFalse(SpotifyLyricsClient.matches(video, info("テレパシ", "Sample Artist", 142_000, "spotify"), false, 0));
        assertFalse(SpotifyLyricsClient.matches(video, info("テレパシ", "Sample Artist", 138_000, "spotify"), false, 3));
        assertFalse(SpotifyLyricsClient.matches(video, info("テレパシ", "Someone Else", 138_000, "spotify"), false, 0));
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

    @Test
    void searchesSpotifyWithTheSongTitleInsteadOfTheWholeVideoTitle() {
        AudioTrackInfo video = new AudioTrackInfo("스텔라이브 (STELLIVE) Universe | '마음악보'", "스텔라이브 StelLive Official and 4 more",
                225_000, "id", false, "https://www.youtube.com/watch?v=id");
        List<String> queries = SpotifyLyricsClient.queries(video);
        assertEquals("마음악보 스텔라이브", queries.get(0));
        assertTrue(queries.size() <= SpotifyLyricsClient.MAX_QUERIES + 1);
        AudioTrackInfo catalog = new AudioTrackInfo("Twinkle", "스텔라이브", 213_000, "x", false, "https://open.spotify.com/track/x", null, "KRA000000001");
        assertEquals(List.of("isrc:KRA000000001", "Twinkle 스텔라이브"), SpotifyLyricsClient.queries(catalog));
    }

    @Test
    void tagsLyricsWithWhereTheyCameFrom() {
        Lyrics found = new Lyrics("t", "a", "x", List.of(), false);
        assertEquals(Lyrics.Source.SPOTIFY, LyricsSources.tagged(found, Lyrics.Source.SPOTIFY).source());
        assertNull(LyricsSources.tagged(null, Lyrics.Source.LRCLIB));
    }

    @Test
    void skipsSpotifyForAWhileAfterAFailure() {
        SpotifyLyricsClient client = new SpotifyLyricsClient(null, null);
        assertFalse(client.paused(1_000));
        client.pause(new java.io.IOException("Read timed out"), 1_000);
        assertTrue(client.paused(1_000 + SpotifyLyricsClient.FAILURE_PAUSE_MS - 1));
        assertFalse(client.paused(1_000 + SpotifyLyricsClient.FAILURE_PAUSE_MS));
    }
}
