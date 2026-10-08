package kr.kro.backas.music.lyrics.sync;

import kr.kro.backas.music.lyrics.LyricLine;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RomajiTest {

    private static final List<String> JAPANESE = List.of(
            "夜の街を一人で歩いた",
            "冷たい風が頬を撫でる",
            "遠くの空に星が光る",
            "明日の約束を思い出す",
            "君の声が聞こえた気がした",
            "静かな部屋で目を閉じる");

    private static final List<String> ROMAJI = List.of(
            "Yoru no machi wo hitori de aruita",
            "Tsumetai kaze ga hoho wo naderu",
            "Tooku no sora ni hoshi ga hikaru",
            "Ashita no yakusoku wo omoidasu",
            "Kimi no koe ga kikoeta ki ga shita",
            "Shizuka na heya de me wo tojiru");

    private static final List<String> ENGLISH = List.of(
            "Walking through the city on my own",
            "Cold wind is brushing past my face",
            "Stars are shining in the distant sky",
            "I remember what we promised for tomorrow",
            "I thought I heard your voice tonight",
            "In a quiet room I close my eyes");

    private static List<WhisperClient.Word> heard(List<String> lines, long firstMs, long gapMs) {
        List<WhisperClient.Word> words = new ArrayList<>();
        long lineAt = firstMs;
        for (String line : lines) {
            long at = lineAt;
            for (int i = 0; i < line.length(); i++) {
                words.add(new WhisperClient.Word(String.valueOf(line.charAt(i)), at, at + 200));
                at += 250;
            }
            lineAt += gapMs;
        }
        return words;
    }

    @Test
    void recognisesRomanisedJapaneseButNotEnglishOrJapaneseScript() {
        assertTrue(Romaji.looksLike(ROMAJI));
        assertFalse(Romaji.looksLike(ENGLISH));
        assertFalse(Romaji.looksLike(JAPANESE));
        assertFalse(Romaji.looksLike(List.of("Yoru no machi")));
        assertEquals("ja", LyricsAligner.languageHint(asLines(ROMAJI, 0)));
        assertEquals("en", LyricsAligner.languageHint(asLines(ENGLISH, 0)));
    }

    @Test
    void readsKanaTheWayRomajiLyricsSpellIt() {
        assertEquals("tookyoo", Romaji.fromKana("トーキョー"));
        assertEquals("kitto", Romaji.fromKana("きっと"));
        assertEquals("chansu", Romaji.fromKana("チャンス"));
        assertEquals("fan", Romaji.fromKana("ファン"));
        assertEquals("macchi", Romaji.fromKana("マッチ"));
        assertEquals("shashin", Romaji.fromKana("しゃしん"));
    }

    @Test
    void foldsSpellingVariantsTogether() {
        assertEquals(Romaji.canonical("toukyou"), Romaji.canonical("Tōkyō"));
        assertEquals(Romaji.canonical("tookyoo"), Romaji.canonical("Tokyo"));
        assertEquals(Romaji.canonical("chotto"), Romaji.canonical("tyotto"));
        assertEquals(Romaji.canonical("shinbun"), Romaji.canonical("shimbun"));
        assertEquals(Romaji.canonical("hou o"), Romaji.canonical("hou wo"));
    }

    @Test
    void readsWhisperJapaneseWordsAsRomajiKeepingTheirTimes() {
        List<WhisperClient.Word> words = Romaji.words(heard(List.of("夜の街を歩いた"), 5_000, 0));
        StringBuilder joined = new StringBuilder();
        for (WhisperClient.Word word : words) joined.append(word.text());
        assertEquals(Romaji.canonical("yoru no machi wo aruita"), joined.toString());
        assertEquals(5_000, words.get(0).startMs());
    }

    @Test
    void timestampsRomajiLyricsFromAJapaneseTranscript() {
        Optional<LyricsTimestamper.Result> result = LyricsTimestamper.timestamp(ROMAJI, heard(JAPANESE, 10_000, 5_000), 60_000);
        assertTrue(result.isPresent());
        assertTrue(result.get().matched() >= 5);
        for (int i = 0; i < ROMAJI.size(); i++) {
            assertEquals(ROMAJI.get(i), result.get().lines().get(i).text());
            assertTrue(Math.abs(result.get().lines().get(i).timeMs() - (10_000 + 5_000L * i)) <= 1_000);
        }
    }

    @Test
    void alignsSyncedRomajiLyricsAgainstAJapaneseTranscript() {
        Optional<LyricsAligner.Alignment> result = LyricsAligner.align(heard(JAPANESE, 12_000, 5_000), asLines(ROMAJI, 10_000), 60_000);
        assertTrue(result.isPresent());
        assertEquals(-2_000, result.get().offsetMs());
    }

    private static List<LyricLine> asLines(List<String> lines, long firstMs) {
        List<LyricLine> out = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) out.add(new LyricLine(firstMs + 5_000L * i, lines.get(i)));
        return out;
    }
}
