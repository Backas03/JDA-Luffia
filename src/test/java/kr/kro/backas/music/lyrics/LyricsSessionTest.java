package kr.kro.backas.music.lyrics;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

class LyricsSessionTest {

    @Test
    void untranslatedLineKeepsAnEmptyRowWhereTheTranslationWouldGo() {
        Lyrics lyrics = new Lyrics("t", "a", null,
                List.of(new LyricLine(0, "first"), new LyricLine(1000, "second"), new LyricLine(2000, "third")), false);
        String text = LyricsSession.body(null, null, lyrics, 1, null, "footer", null, false, false, null).getContent();
        assertTrue(text.startsWith("*first*\n## second\n​\n*third*\n"), text);
    }
}
