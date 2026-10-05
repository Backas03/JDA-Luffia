package kr.kro.backas.music;

import kr.kro.backas.BuildInfo;
import com.sedmelluq.discord.lavaplayer.track.AudioTrackInfo;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TrackCardTest {

    private static AudioTrackInfo info(String title, String author, long lengthMs, boolean stream) {
        return new AudioTrackInfo(title, author, lengthMs, "id", stream, "https://youtu.be/id");
    }

    @Test
    void playingHeaderShowsTitleArtistLengthSourceAndSettings() {
        assertEquals("### [【GUMI】Envy Baby【Kanaria】](https://youtu.be/id)\n-# Kanaria\n-# 2:16 · YouTube\n-# 볼륨 10% · 반복 없음 · AI 추천 켜짐",
                TrackCard.headerText(info("【GUMI】Envy Baby【Kanaria】", "Kanaria", 136_000, false), "YouTube", "볼륨 10% · 반복 없음 · AI 추천 켜짐"));
    }

    @Test
    void finishedHeaderDropsSettingsAndMissingArtist() {
        assertEquals("### [Radio](https://youtu.be/id)\n-# 라이브 · YouTube",
                TrackCard.headerText(info("Radio", "", 0, true), "YouTube", null));
    }

    @Test
    void footerNamesTheBotAndStampsWhenPlaybackFinished() {
        assertEquals("-# 김노래#6163\n-# " + BuildInfo.VERSION, TrackCard.footerText("김노래#6163", 0));
        assertEquals("-# 김노래#6163 <t:1789900000:t> 재생 완료됨\n-# " + BuildInfo.VERSION,
                TrackCard.footerText("김노래#6163", 1_789_900_000_000L));
    }

    @Test
    void titleLinkNeutralisesBracketsThatWouldBreakMarkdown() {
        assertEquals("[(MV) 곡 (feat. X)](https://youtu.be/id)",
                MusicEmbeds.titleLink(info("[MV] 곡 [feat. X]", "a", 1000, false)));
        assertEquals("제목만", MusicEmbeds.titleLink(new AudioTrackInfo("제목만", "a", 1000, "id", false, null)));
    }
}
