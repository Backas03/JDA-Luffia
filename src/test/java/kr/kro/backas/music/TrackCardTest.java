package kr.kro.backas.music;

import com.sedmelluq.discord.lavaplayer.track.AudioTrackInfo;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TrackCardTest {

    private static AudioTrackInfo info(String title, String author, long lengthMs, boolean stream) {
        return new AudioTrackInfo(title, author, lengthMs, "id", stream, "https://youtu.be/id");
    }

    @Test
    void playingHeaderShowsNoteTitleArtistLengthSourceAndSettings() {
        assertEquals("음악을 재생합니다\n### [【GUMI】Envy Baby【Kanaria】](https://youtu.be/id)\n\n-# Kanaria\n-# 2:16 · YouTube\n-# 볼륨 10% · 반복 없음 · AI 추천 켜짐",
                TrackCard.headerText(info("【GUMI】Envy Baby【Kanaria】", "Kanaria", 136_000, false), "YouTube", "볼륨 10% · 반복 없음 · AI 추천 켜짐"));
    }

    @Test
    void finishedHeaderSaysPlayedAndDropsSettingsAndMissingArtist() {
        assertEquals("재생 완료\n### [Radio](https://youtu.be/id)\n\n-# 라이브 · YouTube",
                TrackCard.headerText(info("Radio", "", 0, true), "YouTube", null));
    }

    @Test
    void titleLinkNeutralisesBracketsThatWouldBreakMarkdown() {
        assertEquals("[(MV) 곡 (feat. X)](https://youtu.be/id)",
                MusicEmbeds.titleLink(info("[MV] 곡 [feat. X]", "a", 1000, false)));
        assertEquals("제목만", MusicEmbeds.titleLink(new AudioTrackInfo("제목만", "a", 1000, "id", false, null)));
    }
}
