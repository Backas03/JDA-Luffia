package kr.kro.backas.music.lyrics.sync;

import com.sedmelluq.discord.lavaplayer.track.AudioTrackInfo;
import kr.kro.backas.music.lyrics.LyricLine;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LyricsTranscriberTest {

    private static WhisperClient.Segment segment(long start, long end, String text) {
        return new WhisperClient.Segment(start, end, text);
    }

    private static WhisperClient.Word word(long start, String text) {
        return new WhisperClient.Word(text, start, start + 100);
    }

    @Test
    void turnsSegmentsIntoLinesStartingJustBeforeTheFirstSungWord() {
        WhisperClient.Transcript transcript = new WhisperClient.Transcript(
                List.of(word(10_000, "君"), word(21_500, "夜")),
                List.of(segment(9_000, 14_000, "君の声が聞こえる"),
                        segment(14_000, 15_000, "ご視聴ありがとうございました"),
                        segment(15_000, 20_000, "♪"),
                        segment(20_000, 21_000, "[音楽]"),
                        segment(21_000, 26_000, "夜を越えて")));
        List<LyricLine> lines = LyricsTranscriber.lines(transcript, 40_000);
        assertEquals(List.of(new LyricLine(9_700, "君の声が聞こえる"), new LyricLine(21_200, "夜を越えて"), new LyricLine(26_000, "")), lines);
    }

    @Test
    void keepsLinesInOrderAndSkipsTheOutroBlankWhenTheSongEndsRightAfter() {
        WhisperClient.Transcript transcript = new WhisperClient.Transcript(
                List.of(),
                List.of(segment(5_000, 6_000, "first"), segment(5_000, 7_000, "second")));
        List<LyricLine> lines = LyricsTranscriber.lines(transcript, 8_000);
        assertEquals(List.of(new LyricLine(4_700, "first"), new LyricLine(4_701, "second")), lines);
    }

    @Test
    void rejectsSubtitleHallucinationsAndBracketedNoise() {
        assertTrue(LyricsTranscriber.isLyric("夜を越えて"));
        assertFalse(LyricsTranscriber.isLyric("Thanks for watching!"));
        assertFalse(LyricsTranscriber.isLyric("작사 작곡 편곡 구독"));
        assertFalse(LyricsTranscriber.isLyric("(音楽)"));
        assertFalse(LyricsTranscriber.isLyric("...♪"));
    }

    @Test
    void hintsTheLanguageFromKanaOrHangulInTheTitle() {
        assertEquals("ja", LyricsTranscriber.languageHint(new AudioTrackInfo("ハッピーシンセサイザ", "EasyPop", 200_000, "a", false, null)));
        assertEquals("ko", LyricsTranscriber.languageHint(new AudioTrackInfo("나는 반딧불", "황가람", 200_000, "b", false, null)));
        assertNull(LyricsTranscriber.languageHint(new AudioTrackInfo("Retry Now", "Artist", 200_000, "c", false, null)));
    }
}
