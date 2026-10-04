package kr.kro.backas.music;

import com.sedmelluq.discord.lavaplayer.track.AudioTrackInfo;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TrackCardTest {

    private static AudioTrackInfo info(String title, String author, long lengthMs, boolean stream) {
        return new AudioTrackInfo(title, author, lengthMs, "id", stream, "https://youtu.be/id");
    }

    @Test
    void headerFollowsTheQueueViewStyleWithAPlayingNoteOnTop() {
        assertEquals("-# 음악을 재생합니다\n### [DECO*27 - ヴァンパイア](https://youtu.be/id)\nDECO*27\n-# 3:00 · YouTube · AI 추천 · 박카스 · 노래 봇 김노래#6163",
                TrackCard.headerText(info("DECO*27 - ヴァンパイア", "DECO*27", 180_000, false), "김노래#6163", "YouTube", "AI 추천 · 박카스", true));
    }

    @Test
    void finishedHeaderSaysPlayedAndSkipsMissingArtistOrRequester() {
        assertEquals("-# 재생 완료\n### [Radio](https://youtu.be/id)\n-# 라이브 · YouTube · 노래 봇 김노래",
                TrackCard.headerText(info("Radio", "", 0, true), "김노래", "YouTube", null, false));
    }

    @Test
    void titleLinkNeutralisesBracketsThatWouldBreakMarkdown() {
        assertEquals("[(MV) 곡 (feat. X)](https://youtu.be/id)",
                MusicEmbeds.titleLink(info("[MV] 곡 [feat. X]", "a", 1000, false)));
        assertEquals("제목만", MusicEmbeds.titleLink(new AudioTrackInfo("제목만", "a", 1000, "id", false, null)));
    }
}
