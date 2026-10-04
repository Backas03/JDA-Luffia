package kr.kro.backas.music;

import com.sedmelluq.discord.lavaplayer.track.AudioTrackInfo;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TrackCardTest {

    private static AudioTrackInfo info(String title, String author, long lengthMs, boolean stream) {
        return new AudioTrackInfo(title, author, lengthMs, "id", stream, "https://youtu.be/id");
    }

    @Test
    void headerLooksLikeTheOldPlayEmbedWhilePlaying() {
        assertEquals("**DECO*27**\n### [DECO*27 - Monitoring](https://youtu.be/id)\n음악을 재생합니다\n"
                        + "**노래 봇** 김노래#6163 · **재생 시간** 3분 2초 · **출처** YouTube",
                TrackCard.headerText(info("DECO*27 - Monitoring", "DECO*27", 182_000, false), "김노래#6163", "YouTube", true));
    }

    @Test
    void collapsedHeaderDropsThePlayingNoteAndMissingArtist() {
        assertEquals("### [Radio](https://youtu.be/id)\n**노래 봇** 김노래 · **재생 시간** 실시간 스트리밍 · **출처** YouTube",
                TrackCard.headerText(info("Radio", "", 0, true), "김노래", "YouTube", false));
    }

    @Test
    void titleLinkNeutralisesBracketsThatWouldBreakMarkdown() {
        assertEquals("[(MV) 곡 (feat. X)](https://youtu.be/id)",
                MusicEmbeds.titleLink(info("[MV] 곡 [feat. X]", "a", 1000, false)));
        assertEquals("제목만", MusicEmbeds.titleLink(new AudioTrackInfo("제목만", "a", 1000, "id", false, null)));
    }
}
