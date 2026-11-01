package kr.kro.backas.music.lyrics;

import org.junit.jupiter.api.Test;

import java.util.List;

import static kr.kro.backas.music.lyrics.LyricsJumps.Jump.INTERLUDE;
import static kr.kro.backas.music.lyrics.LyricsJumps.Jump.NEXT_LINE;
import static kr.kro.backas.music.lyrics.LyricsJumps.Jump.PREVIOUS_LINE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class LyricsJumpsTest {

    private static final List<LyricLine> SONG = List.of(
            new LyricLine(15_000, "first"),
            new LyricLine(19_000, "second"),
            new LyricLine(23_000, "third"),
            new LyricLine(27_000, ""),
            new LyricLine(52_000, "verse two"),
            new LyricLine(56_000, "and on"));

    @Test
    void interludeJumpLandsTwoSecondsBeforeTheSingingComesBack() {
        assertEquals(50_000L, LyricsJumps.target(SONG, 30_000, INTERLUDE));
        assertEquals(13_000L, LyricsJumps.target(SONG, 2_000, INTERLUDE));
    }

    @Test
    void interludeJumpWorksAfterALongLastLineToo() {
        List<LyricLine> held = List.of(new LyricLine(10_000, "hold this"), new LyricLine(40_000, "next"));
        assertEquals(38_000L, LyricsJumps.target(held, 20_000, INTERLUDE));
        assertNull(LyricsJumps.target(held, 12_000, INTERLUDE));
    }

    @Test
    void noInterludeJumpWhileSingingOrRightBeforeTheNextLine() {
        assertNull(LyricsJumps.target(SONG, 20_000, INTERLUDE));
        assertNull(LyricsJumps.target(SONG, 49_500, INTERLUDE));
        assertNull(LyricsJumps.target(SONG, 60_000, INTERLUDE));
    }

    @Test
    void nextLineSkipsRestsAndTheLineThatIsAboutToStart() {
        assertEquals(19_000L, LyricsJumps.target(SONG, 16_000, NEXT_LINE));
        assertEquals(52_000L, LyricsJumps.target(SONG, 24_000, NEXT_LINE));
        assertEquals(23_000L, LyricsJumps.target(SONG, 18_800, NEXT_LINE));
        assertNull(LyricsJumps.target(SONG, 57_000, NEXT_LINE));
    }

    @Test
    void previousLineRestartsTheCurrentLineFirst() {
        assertEquals(19_000L, LyricsJumps.target(SONG, 21_000, PREVIOUS_LINE));
        assertEquals(15_000L, LyricsJumps.target(SONG, 19_500, PREVIOUS_LINE));
        assertEquals(23_000L, LyricsJumps.target(SONG, 40_000, PREVIOUS_LINE));
        assertNull(LyricsJumps.target(SONG, 5_000, PREVIOUS_LINE));
    }
}
