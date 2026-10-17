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

    private static WhisperClient.Word word(String text, long start, long end) {
        return new WhisperClient.Word(text, start, end);
    }

    @Test
    void turnsSegmentsIntoLinesWithRestsBeforeAfterAndBetweenTheSinging() {
        WhisperClient.Transcript transcript = new WhisperClient.Transcript(
                List.of(word("君", 10_000, 10_200), word("の", 10_200, 10_400), word("声", 10_400, 10_800),
                        word("が", 10_800, 11_000), word("聞", 11_000, 11_400), word("こえる", 11_400, 12_000),
                        word("夜", 21_500, 21_700), word("を", 21_700, 21_900), word("越", 21_900, 22_300), word("えて", 22_300, 23_000)),
                List.of(segment(9_000, 14_000, "君の声が聞こえる"),
                        segment(14_000, 15_000, "ご視聴ありがとうございました"),
                        segment(15_000, 20_000, "♪"),
                        segment(20_000, 21_000, "[音楽]"),
                        segment(21_000, 26_000, "夜を越えて")));
        List<LyricLine> lines = LyricsTranscriber.lines(transcript, 40_000);
        assertEquals(List.of(new LyricLine(0, ""), new LyricLine(9_700, "君の声が聞こえる"), new LyricLine(12_500, ""),
                new LyricLine(21_200, "夜を越えて"), new LyricLine(23_500, "")), lines);
    }

    @Test
    void keepsLinesInOrderAndSkipsTheOutroBlankWhenTheSongEndsRightAfter() {
        WhisperClient.Transcript transcript = new WhisperClient.Transcript(
                List.of(),
                List.of(segment(2_000, 3_000, "first"), segment(2_000, 4_000, "second")));
        List<LyricLine> lines = LyricsTranscriber.lines(transcript, 5_000);
        assertEquals(List.of(new LyricLine(1_700, "first"), new LyricLine(1_701, "second")), lines);
    }

    @Test
    void startsLinesLateEnoughWhenTheFirstWordIsSnappedToTheSegmentOrStretchedBackwards() {
        WhisperClient.Transcript transcript = new WhisperClient.Transcript(
                List.of(word("思", 1_000, 1_400), word("い", 1_760, 1_800), word("切", 2_010, 2_200), word("って", 2_400, 3_000),
                        word("また", 3_800, 4_900), word("明", 4_900, 5_300), word("日", 5_300, 5_700), word("の", 5_700, 6_000),
                        word("夜", 6_000, 6_500), word("に", 6_500, 7_000),
                        word("「", 7_000, 7_360), word("今", 7_360, 7_720), word("の", 7_720, 7_900), word("リーダー", 7_900, 9_000)),
                List.of(segment(1_000, 3_000, "思い切って"), segment(3_000, 7_000, "また明日の夜に"), segment(7_000, 9_000, "「今のリーダー」")));
        List<LyricLine> lines = LyricsTranscriber.lines(transcript, 10_000);
        assertEquals(List.of(new LyricLine(1_210, "思い切って"), new LyricLine(4_200, "また明日の夜に"), new LyricLine(7_170, "「今のリーダー」")), lines);
    }

    @Test
    void movesLinesSmearedOverAnInterludeToTheirEndAndCutsSmearedTailsShort() {
        WhisperClient.Transcript transcript = new WhisperClient.Transcript(
                List.of(word("笑", 53_860, 54_860), word("わ", 54_860, 55_860), word("せて", 55_860, 57_860),
                        word("あれ", 59_650, 63_440), word("から", 63_440, 68_020), word("世界", 68_020, 72_600), word("は", 72_600, 74_890),
                        word("変", 74_890, 77_170), word("わ", 77_180, 79_470), word("った", 79_470, 84_050), word("って", 84_050, 88_640),
                        word("本", 88_900, 89_100), word("気", 89_100, 89_300), word("で", 89_300, 89_500),
                        word("未", 89_600, 93_880), word("来", 93_880, 98_160), word("の", 98_160, 100_000)),
                List.of(segment(52_000, 57_860, "笑わせて"), segment(58_860, 88_640, "あれから世界は変わったって"),
                        segment(88_640, 100_000, "本気で未来の")));
        List<LyricLine> lines = LyricsTranscriber.lines(transcript, 120_000);
        assertEquals(List.of(new LyricLine(0, ""), new LyricLine(54_310, "笑わせて"), new LyricLine(58_360, ""),
                new LyricLine(85_590, "あれから世界は変わったって"), new LyricLine(88_600, "本気で未来の"), new LyricLine(90_850, "")), lines);
    }

    @Test
    void rejectsSubtitleHallucinationsAndBracketedNoise() {
        assertTrue(LyricsTranscriber.isLyric("夜を越えて"));
        assertFalse(LyricsTranscriber.isLyric("Thanks for watching!"));
        assertFalse(LyricsTranscriber.isLyric("작사 작곡 편곡 구독"));
        assertFalse(LyricsTranscriber.isLyric("(音楽)"));
        assertFalse(LyricsTranscriber.isLyric("...♪"));
    }

    private static AudioTrackInfo info(String title, String author) {
        return new AudioTrackInfo(title, author, 200_000, "id", false, null);
    }

    @Test
    void hintsTheLanguageFromKanaOrHangulInTheTitleBeforeTheTaggedLanguage() {
        assertEquals("ja", LyricsTranscriber.languageHint(info("ハッピーシンセサイザ", "EasyPop"), "en"));
        assertEquals("ko", LyricsTranscriber.languageHint(info("나는 반딧불", "황가람"), null));
        assertNull(LyricsTranscriber.languageHint(info("Retry Now", "Artist"), null));
    }

    @Test
    void fallsBackToTheTaggedVocalLanguageForLatinTitles() {
        assertEquals("ja", LyricsTranscriber.languageHint(info("Idol", "YOASOBI"), "ja"));
        assertEquals("en", LyricsTranscriber.languageHint(info("Shape of You", "Ed Sheeran"), "en"));
        assertEquals("zh", LyricsTranscriber.languageHint(info("Tian Mi Mi", "Teresa Teng"), "zh"));
        assertNull(LyricsTranscriber.languageHint(info("Nocturne", "Chopin"), "instrumental"));
        assertNull(LyricsTranscriber.languageHint(info("Unknown", "Artist"), "other"));
    }

    @Test
    void promptsWithTheArtistAndTitleStrippedOfVideoNoise() {
        assertEquals("Ayase / YOASOBI - YOASOBI「夜に駆ける」", LyricsTranscriber.prompt(info("YOASOBI「夜に駆ける」 Official Music Video", "Ayase / YOASOBI")));
        assertEquals("Ed Sheeran - Shape of You", LyricsTranscriber.prompt(info("Shape of You [Official Video] (Lyrics)", "Ed Sheeran - Topic")));
        assertEquals("아이유 - 좋은 날", LyricsTranscriber.prompt(info("좋은 날 (Good Day) MV", "아이유 Official")));
        assertEquals("Ayase - YOASOBI - アイドル", LyricsTranscriber.prompt(info("YOASOBI - アイドル (Idol) 【Music Video】", "Ayase")));
        assertEquals("YOASOBI - アイドル", LyricsTranscriber.prompt(info("YOASOBI - アイドル (Idol) 【Music Video】", "YOASOBI")));
        assertEquals("Official髭男dism - Pretender", LyricsTranscriber.prompt(info("Pretender", "Official髭男dism")));
    }

    @Test
    void promptIsCappedAndEmptyTitlesGiveNoPrompt() {
        String prompt = LyricsTranscriber.prompt(info("a".repeat(150), "b".repeat(20)));
        assertEquals(LyricsTranscriber.MAX_PROMPT_LENGTH, prompt.codePointCount(0, prompt.length()));
        assertTrue(prompt.startsWith("bbbb"));
        assertNull(LyricsTranscriber.prompt(info("[Official MV]", "")));
        assertEquals("Artist", LyricsTranscriber.prompt(info("(Audio)", "Artist")));
    }
}
