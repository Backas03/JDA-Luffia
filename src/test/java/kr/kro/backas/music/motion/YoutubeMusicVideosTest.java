package kr.kro.backas.music.motion;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class YoutubeMusicVideosTest {

    private static final long LENGTH = 275_000;

    private static YoutubeMusicVideos.Candidate video(String id, String title, String author, long lengthMs) {
        return new YoutubeMusicVideos.Candidate(id, title, author, lengthMs);
    }

    private static List<String> ids(List<YoutubeMusicVideos.Candidate> candidates) {
        List<String> ids = new ArrayList<>();
        for (YoutubeMusicVideos.Candidate candidate : candidates) ids.add(candidate.videoId());
        return ids;
    }

    @Test
    void theOfficialVideoComesBeforeOtherUploadsOfTheSameSong() {
        List<YoutubeMusicVideos.Candidate> picked = YoutubeMusicVideos.pick("Amore", "ReoNa", LENGTH, "played", List.of(
                video("lyrics", "ReoNa - Amore (Lyrics)", "Some Channel", 274_000),
                video("mv", "ReoNa 『Amore』 -Music Video-", "ReoNa Official YouTube Channel", 280_000)));
        assertEquals(List.of("mv", "lyrics"), ids(picked));
    }

    @Test
    void theVideoThatIsAlreadyPlayingAndAudioOnlyUploadsAreSkipped() {
        List<YoutubeMusicVideos.Candidate> picked = YoutubeMusicVideos.pick("Amore", "ReoNa", LENGTH, "played", List.of(
                video("played", "ReoNa - Amore MV", "ReoNa", 275_000),
                video("topic", "Amore", "ReoNa - Topic", 275_000)));
        assertTrue(picked.isEmpty());
    }

    @Test
    void aDifferentSongLengthOrArtistIsNotTheSameSong() {
        List<YoutubeMusicVideos.Candidate> picked = YoutubeMusicVideos.pick("Amore", "ReoNa", LENGTH, "played", List.of(
                video("long", "ReoNa - Amore MV (Full Drama Ver.)", "ReoNa", 420_000),
                video("other", "Amore MV", "Another Artist", 275_000),
                video("unrelated", "ReoNa - ANIMA MV", "ReoNa", 275_000)));
        assertTrue(picked.isEmpty());
    }

    @Test
    void coversAndLiveTakesAreSkippedUnlessThatIsWhatIsPlaying() {
        List<YoutubeMusicVideos.Candidate> results = List.of(
                video("cover", "Amore / ReoNa (Cover)", "Someone", 275_000),
                video("live", "ReoNa - Amore (Live at Budokan)", "ReoNa", 276_000));
        assertTrue(YoutubeMusicVideos.pick("Amore", "ReoNa", LENGTH, "played", results).isEmpty());
        assertEquals(List.of("live"), ids(YoutubeMusicVideos.pick("Amore - Live", "ReoNa", LENGTH, "played", results)));
    }

    @Test
    void featuredArtistsAndVersionNotesDoNotGetInTheWay() {
        List<YoutubeMusicVideos.Candidate> picked = YoutubeMusicVideos.pick("Spica (feat. Someone) - 2024 Remaster", "Rokudenashi, Someone", LENGTH, "played",
                List.of(video("mv", "Rokudenashi - Spica【Official Music Video】", "ロクデナシ", 277_000)));
        assertEquals(List.of("mv"), ids(picked));
    }

    @Test
    void onlyAFewCandidatesAreWorthTrying() {
        List<YoutubeMusicVideos.Candidate> results = new ArrayList<>();
        for (int i = 0; i < 8; i++) results.add(video("v" + i, "ReoNa - Amore", "ReoNa", 275_000));
        assertEquals(YoutubeMusicVideos.MAX_CANDIDATES, YoutubeMusicVideos.pick("Amore", "ReoNa", LENGTH, "played", results).size());
    }

    @Test
    void aTitleOrArtistTooShortToMatchOnFindsNothing() {
        assertTrue(YoutubeMusicVideos.pick("?", "ReoNa", LENGTH, "played", List.of(video("a", "ReoNa - ? MV", "ReoNa", 275_000))).isEmpty());
        assertTrue(YoutubeMusicVideos.pick("Amore", null, LENGTH, "played", List.of(video("a", "Amore MV", "ReoNa", 275_000))).isEmpty());
    }
}
