package kr.kro.backas.music.ai;

import com.sedmelluq.discord.lavaplayer.track.AudioTrackInfo;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ArtistVarietyTest {

    private record Song(String artist, String title) {
    }

    private static AudioTrackInfo info(String title, String author) {
        return new AudioTrackInfo(title, author, 200_000, "id-" + title, false, null);
    }

    private static Set<String> artists(Song song) {
        return ArtistVariety.names(song.artist());
    }

    @Test
    void channelNamesAndTitlesPointToTheSameArtist() {
        Set<String> expected = Set.of("mrsgreenapple");
        assertEquals(expected, ArtistVariety.names(info("ライラック", "Mrs. GREEN APPLE - Topic")));
        assertEquals(expected, ArtistVariety.names(info("Mrs. GREEN APPLE「lulu.」Official Music Video", "Mrs. GREEN APPLE")));
        assertEquals(expected, ArtistVariety.names(info("Mrs. GREEN APPLE - 共犯", "some label")));
        assertEquals(Set.of("newjeans"), ArtistVariety.names(info("NewJeans (뉴진스) 'Super Shy' Official MV", "HYBE LABELS")));
        assertEquals(Set.of("ebidan", "超特急", "mlk", "原因は自分にある"),
                ArtistVariety.names("EBiDAN (恵比寿学園男子部), 超特急, M!LK & 原因は自分にある。"));
    }

    @Test
    void capsSongsPerArtistInRankOrder() {
        List<Song> chart = List.of(
                new Song("Mrs. GREEN APPLE", "a"), new Song("Mrs. GREEN APPLE", "b"), new Song("M!LK", "c"),
                new Song("Mrs. GREEN APPLE", "d"), new Song("YAO", "e"), new Song("M!LK", "f"),
                new Song("SixTONES", "g"));
        List<Song> diverse = ArtistVariety.pick(chart, ArtistVarietyTest::artists, 1, 4);
        assertEquals(List.of("a", "c", "e", "g"), diverse.stream().map(Song::title).toList());
        List<Song> relaxed = ArtistVariety.pick(chart, ArtistVarietyTest::artists, 2, 5);
        assertEquals(Set.of("a", "b", "c", "e", "f"), Set.copyOf(relaxed.stream().map(Song::title).toList()));
    }

    @Test
    void fillsWithRepeatsOnlyWhenTheListRunsOut() {
        List<Song> chart = List.of(new Song("A", "1"), new Song("A", "2"), new Song("B", "3"));
        List<Song> picked = ArtistVariety.pick(chart, ArtistVarietyTest::artists, 1, 3);
        assertEquals(3, picked.size());
        assertEquals(List.of("1", "3", "2"), picked.stream().map(Song::title).toList());
    }

    @Test
    void spreadAvoidsTheSameArtistBackToBack() {
        List<Song> songs = List.of(new Song("A", "1"), new Song("A", "2"), new Song("B", "3"), new Song("C", "4"));
        List<Song> ordered = ArtistVariety.spread(songs, ArtistVarietyTest::artists);
        for (int i = 1; i < ordered.size(); i++) {
            assertTrue(!ordered.get(i).artist().equals(ordered.get(i - 1).artist()));
        }
    }
}
