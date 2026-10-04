package kr.kro.backas.music;

import com.sedmelluq.discord.lavaplayer.track.AudioTrackInfo;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TrackCardTest {

    private static AudioTrackInfo info(String title, String author, long lengthMs, boolean stream) {
        return new AudioTrackInfo(title, author, lengthMs, "id", stream, "https://youtu.be/id");
    }

    @Test
    void headerShowsTitleLinkArtistAndOneSmallMetaLine() {
        assertEquals("### [Brand New](https://youtu.be/id)\nMrs. GREEN APPLE\n-# 김노래 · 3:31 · YouTube · 요청 박카스",
                TrackCard.headerText(info("Brand New", "Mrs. GREEN APPLE", 211_000, false), "김노래", "YouTube", "요청 박카스"));
    }

    @Test
    void headerSkipsMissingArtistAndRequesterAndMarksStreams() {
        assertEquals("### [Radio](https://youtu.be/id)\n-# 김노래 · 라이브 · YouTube",
                TrackCard.headerText(info("Radio", "", 0, true), "김노래", "YouTube", null));
    }

    @Test
    void titleLinkNeutralisesBracketsThatWouldBreakMarkdown() {
        assertEquals("[(MV) 곡 (feat. X)](https://youtu.be/id)",
                MusicEmbeds.titleLink(info("[MV] 곡 [feat. X]", "a", 1000, false)));
        assertEquals("제목만", MusicEmbeds.titleLink(new AudioTrackInfo("제목만", "a", 1000, "id", false, null)));
    }
}
