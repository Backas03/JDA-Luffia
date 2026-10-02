package kr.kro.backas.music.lyrics;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

class TranslationAlignmentTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final List<String> SONG = List.of(
            "「仕方ないから付き合ってあげる」って",
            "隣の君が笑った",
            "胸が鳴る音がした",
            "(鳴る音がした)");

    private static List<JsonNode> entries(String json) throws Exception {
        List<JsonNode> list = new ArrayList<>();
        for (JsonNode node : MAPPER.readTree(json).path("t")) list.add(node);
        return list;
    }

    @Test
    void keepsCorrectlyNumberedLines() throws Exception {
        Map<Integer, String> byNumber = new HashMap<>();
        TranslationClient.collectAligned(entries("""
                {"t": [{"n": 1, "s": "「仕方", "k": "A"}, {"n": 2, "s": "隣の君", "k": "B"},
                       {"n": 3, "s": "胸が鳴", "k": "C"}, {"n": 4, "s": "(鳴る", "k": "D"}]}"""), byNumber, SONG);
        assertEquals(Map.of(1, "A", 2, "B", 3, "C", 4, "D"), byNumber);
    }

    @Test
    void realignsWhenTheModelMergedALine() throws Exception {
        Map<Integer, String> byNumber = new HashMap<>();
        TranslationClient.collectAligned(entries("""
                {"t": [{"n": 1, "s": "仕方な", "k": "A+B"}, {"n": 2, "s": "胸が鳴", "k": "C"},
                       {"n": 3, "s": "鳴る音", "k": "D"}, {"n": 4, "s": "?", "k": "X"}]}"""), byNumber, SONG);
        assertEquals("A+B", byNumber.get(1));
        assertNull(byNumber.get(2));
        assertEquals("C", byNumber.get(3));
        assertEquals("D", byNumber.get(4));
        assertFalse(byNumber.containsValue("X"));
    }

    @Test
    void ignoresFullWidthAndBracketDifferences() {
        assertEquals(4, TranslationClient.alignedLine(SONG, 4, "（鳴る", new HashMap<>()));
        assertEquals(2, TranslationClient.alignedLine(List.of("x", "I'm running tonight"), 2, "Im run", new HashMap<>()));
    }

    @Test
    void stripsMarkupFromTranslations() throws Exception {
        assertEquals("조금이라도 웃을 수 있게", TranslationClient.cleanTranslation("조금이라도 웃을 수 있게</div>"));
        assertEquals("안아줘!", TranslationClient.cleanTranslation("<span class=\"x\">안아줘!</span>"));
        assertEquals("<3 사랑해", TranslationClient.cleanTranslation("<3 사랑해"));
        Map<Integer, String> byNumber = new HashMap<>();
        TranslationClient.collectAligned(entries("""
                {"t": [{"n": 2, "s": "隣の君", "k": "옆의 네가 웃었어<br/>"}]}"""), byNumber, SONG);
        assertEquals("옆의 네가 웃었어", byNumber.get(2));
    }

    @Test
    void trustsNumberWhenPrefixIsMissing() {
        assertEquals(3, TranslationClient.alignedLine(SONG, 3, "", new HashMap<>()));
        assertEquals(-1, TranslationClient.alignedLine(SONG, 9, "", new HashMap<>()));
    }
}
