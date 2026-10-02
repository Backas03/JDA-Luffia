package kr.kro.backas.music.ai;

import com.sedmelluq.discord.lavaplayer.track.AudioTrackInfo;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class YoutubeLookupTest {

    private static AudioTrackInfo info(String id, String title, String author) {
        return new AudioTrackInfo(title, author, 200_000, id, false, "https://youtu.be/" + id);
    }

    private static boolean sameSong(AudioTrackInfo first, AudioTrackInfo second) {
        Set<String> seen = new HashSet<>();
        YoutubeLookup.markSeen(seen, first);
        return !YoutubeLookup.markSeen(seen, second);
    }

    @Test
    void detectsReuploadsOfTheSameSong() {
        assertTrue(sameSong(
                info("a1", "wowaka『ローリンガール』feat. 初音ミク / wowaka - Rollin Girl (Official Video) ft. Hatsune Miku", "ヒトリエ / wowaka"),
                info("a2", "ローリンガール", "someone")));
        assertTrue(sameSong(
                info("b1", "DECO*27 - ヴァンパイア feat. 初音ミク", "DECO*27"),
                info("b2", "ヴァンパイア / DECO*27 feat.初音ミク", "DECO*27")));
        assertTrue(sameSong(
                info("c1", "Lemon [Official MV]", "米津玄師"),
                info("c2", "Lemon (Remix)", "someone")));
    }

    @Test
    void keepsDifferentSongsByTheSameArtist() {
        assertFalse(sameSong(info("d1", "KING / Kanaria feat. GUMI", "Kanaria"), info("d2", "QUEEN / Kanaria feat. GUMI", "Kanaria")));
        assertFalse(sameSong(
                info("e1", "wowaka『裏表ラバーズ』feat. 初音ミク", "ヒトリエ / wowaka"),
                info("e2", "wowaka『アンノウン・マザーグース』feat. 初音ミク", "ヒトリエ / wowaka")));
    }

    @Test
    void flagsCoverAndLiveVariants() {
        assertTrue(YoutubeLookup.isVariant(info("f1", "砂の惑星 歌ってみた【cover】", "x")));
        assertTrue(YoutubeLookup.isVariant(info("f2", "【LIVE】バラ色の日々 -Tokyo Dome-", "x")));
        assertFalse(YoutubeLookup.isVariant(info("f3", "DECO*27 - ヴァンパイア feat. 初音ミク", "DECO*27")));
        assertTrue(YoutubeLookup.wantsVariants("커버곡 위주로 틀어줘"));
        assertFalse(YoutubeLookup.wantsVariants("보카로곡 20곡"));
    }
}
