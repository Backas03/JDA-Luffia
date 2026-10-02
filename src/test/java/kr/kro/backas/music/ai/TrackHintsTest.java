package kr.kro.backas.music.ai;

import com.sedmelluq.discord.lavaplayer.track.AudioTrackInfo;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TrackHintsTest {

    private static AudioTrackInfo info(String title, String artist, String isrc) {
        return new AudioTrackInfo(title, artist, 200_000, "id", false, "https://open.spotify.com/track/id", null, isrc);
    }

    @Test
    void appendsTheDecidedTagAsAHint() {
        AudioTrackInfo info = info("LEFT RIGHT", "XG", "JPB602300001");
        String line = TrackHints.describe(info, new AiTrackTagger.Tag("k-pop", "ko", 4));
        assertTrue(line.startsWith(TrackHints.describe(info)));
        assertTrue(line.endsWith(" | tag: k-pop, ko"));
    }

    @Test
    void describesWithoutATagWhenNoneIsKnown() {
        AudioTrackInfo info = info("花束", "back number", "JPPO01100001");
        assertEquals(TrackHints.describe(info), TrackHints.describe(info, null));
        assertTrue(TrackHints.describe(info).contains("isrc: JP"));
    }

    @Test
    void hintGuideExplainsThatIsrcIsOnlyAWeakHint() {
        assertTrue(TrackHints.HINT_GUIDE.contains("only a weak hint"));
        assertTrue(TrackHints.HINT_GUIDE.contains("one scene only"));
    }
}
