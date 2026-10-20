package kr.kro.backas.music.lyrics.sync;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AsrClientTest {

    @Test
    void parsesEndpointListWithOptionalLabels() {
        List<AsrClient.Endpoint> endpoints = AsrClient.parse("http://a:8000/|GPU A, http://b.example:8000 ,, ");
        assertEquals(2, endpoints.size());
        assertEquals("http://a:8000", endpoints.get(0).baseUrl());
        assertEquals("GPU A", endpoints.get(0).label());
        assertEquals("b.example", endpoints.get(1).label());
        assertTrue(AsrClient.parse(null).isEmpty());
    }

    @Test
    void unconfiguredClientIsNeverAvailable() {
        AsrClient client = new AsrClient("");
        assertFalse(client.isConfigured());
        assertFalse(client.isAvailable());
        assertTrue(new AsrClient("http://localhost:1").isAvailable());
    }

    @Test
    void parsesWordTimestampsFromTheServerResponse() throws Exception {
        String json = "{\"text\":\"hi there\",\"words\":[{\"word\":\" hi\",\"start\":0.5,\"end\":0.8},"
                + "{\"word\":\"there\",\"start\":0.9,\"end\":1.25},{\"word\":\"   \",\"start\":2,\"end\":2}]}";
        List<AsrClient.Word> words = AsrClient.parseWords(json);
        assertEquals(2, words.size());
        assertEquals("hi", words.get(0).text());
        assertEquals(500, words.get(0).startMs());
        assertEquals(1_250, words.get(1).endMs());
    }

    @Test
    void readsWordsInsideSegmentsFromWhisperCppAndDropsBrokenCharacters() throws Exception {
        String json = "{\"text\":\"君から\",\"segments\":[{\"start\":40.0,\"end\":46.0,\"words\":["
                + "{\"word\":\"君\",\"start\":40.0,\"end\":40.71,\"t_dtw\":-1},{\"word\":\"�\",\"start\":40.71,\"end\":41.0}]},"
                + "{\"start\":46.0,\"end\":52.0,\"words\":[{\"word\":\" から\",\"start\":46.29,\"end\":47.13}]}]}";
        List<AsrClient.Word> words = AsrClient.parseWords(json);
        assertEquals(2, words.size());
        assertEquals("君", words.get(0).text());
        assertEquals(40_000, words.get(0).startMs());
        assertEquals("から", words.get(1).text());
        assertEquals(47_130, words.get(1).endMs());
    }

    @Test
    void asksTheServerToDetectTheLanguageWhenThereIsNoHint() throws Exception {
        for (String language : new String[]{null, "", "  "}) {
            String text = new String(AsrClient.multipart("xyz", new byte[]{1}, language, null), StandardCharsets.UTF_8);
            assertTrue(text.contains("name=\"language\"\r\n\r\nauto\r\n"));
        }
        String hinted = new String(AsrClient.multipart("xyz", new byte[]{1}, " ko ", null), StandardCharsets.UTF_8);
        assertTrue(hinted.contains("name=\"language\"\r\n\r\nko\r\n"));
    }

    @Test
    void restoresKoreanSyllablesThatWhisperCppSplitAcrossTokens() throws Exception {
        String json = "{\"segments\":[{\"start\":0.0,\"end\":12.0,\"text\":\" 별을 쫓아 달려왔죠\",\"words\":["
                + "{\"word\":\" 별\",\"start\":0.5,\"end\":1.5},{\"word\":\"을\",\"start\":1.5,\"end\":2.99},"
                + "{\"word\":\" �\",\"start\":2.99,\"end\":3.99},{\"word\":\"�\",\"start\":3.99,\"end\":4.48},"
                + "{\"word\":\"아\",\"start\":4.48,\"end\":5.96},{\"word\":\" 달�\",\"start\":5.97,\"end\":7.97},"
                + "{\"word\":\"��\",\"start\":7.97,\"end\":8.96},{\"word\":\"왔\",\"start\":8.96,\"end\":10.45},"
                + "{\"word\":\"죠\",\"start\":10.45,\"end\":12.0}]}]}";
        List<AsrClient.Word> words = AsrClient.parseWords(json);
        assertEquals(List.of(
                new AsrClient.Word("별", 500, 1_500),
                new AsrClient.Word("을", 1_500, 2_990),
                new AsrClient.Word("쫓", 2_990, 4_480),
                new AsrClient.Word("아", 4_480, 5_960),
                new AsrClient.Word("달려", 5_970, 8_960),
                new AsrClient.Word("왔", 8_960, 10_450),
                new AsrClient.Word("죠", 10_450, 12_000)), words);
    }

    @Test
    void restoresACharacterSplitAtTheEndOfASegment() {
        List<AsrClient.Word> tokens = List.of(new AsrClient.Word(" 끝", 0, 100), new AsrClient.Word("�", 100, 200),
                new AsrClient.Word("�", 200, 300));
        assertEquals(List.of(new AsrClient.Word(" 끝", 0, 100), new AsrClient.Word("까", 100, 300)),
                AsrClient.repairSplitCharacters(" 끝까", tokens));
    }

    @Test
    void keepsTheOldBehaviourWhenTheSegmentTextCannotBeTrusted() {
        List<AsrClient.Word> tokens = List.of(new AsrClient.Word(" 별", 0, 100), new AsrClient.Word("�", 100, 200));
        assertNull(AsrClient.repairSplitCharacters(" 별�", tokens));
        assertNull(AsrClient.repairSplitCharacters(" 달", tokens));
        assertNull(AsrClient.repairSplitCharacters(" 별 a", tokens));
        assertNull(AsrClient.repairSplitCharacters(" 별을", List.of(new AsrClient.Word(" 별을", 0, 100))));
    }

    @Test
    void readsSegmentTextAndTimesForTranscription() throws Exception {
        String json = "{\"segments\":[{\"start\":1.5,\"end\":3.25,\"text\":\" 君の声 \"},"
                + "{\"start\":4,\"end\":5,\"text\":\"�\"},{\"start\":6,\"end\":5.5,\"text\":\"夜\"}]}";
        List<AsrClient.Segment> segments = AsrClient.parseSegments(json);
        assertEquals(List.of(new AsrClient.Segment(1_500, 3_250, "君の声"), new AsrClient.Segment(6_000, 6_000, "夜")), segments);
        assertTrue(AsrClient.parseSegments("{\"text\":\"x\"}").isEmpty());
    }

    @Test
    void postsToTheConfiguredPathOrTheOpenAiPath() {
        assertEquals("http://a:8000/v1/audio/transcriptions", AsrClient.transcriptionUrl("http://a:8000"));
        assertEquals("http://b:8178/inference", AsrClient.transcriptionUrl("http://b:8178/inference"));
        assertEquals("http://b:8178/inference", AsrClient.transcriptionUrl(AsrClient.parse("http://b:8178/inference/").get(0).baseUrl()));
    }

    @Test
    void multipartBodyCarriesTheWavAndTheLanguageHint() throws Exception {
        byte[] body = AsrClient.multipart("xyz", new byte[]{1, 2, 3}, "ja", null);
        String text = new String(body, StandardCharsets.ISO_8859_1);
        assertTrue(text.startsWith("--xyz\r\nContent-Disposition: form-data; name=\"file\"; filename=\"audio.wav\""));
        assertTrue(text.contains("name=\"language\"\r\n\r\nja\r\n"));
        assertTrue(text.contains("name=\"response_format\"\r\n\r\nverbose_json\r\n"));
        assertTrue(text.contains("name=\"max_context\"\r\n\r\n0\r\n"));
        assertFalse(text.contains("name=\"prompt\""));
        assertTrue(text.endsWith("--xyz--\r\n"));
    }

    @Test
    void multipartBodyCarriesThePromptAsUtf8WhenGiven() throws Exception {
        byte[] body = AsrClient.multipart("xyz", new byte[]{1}, null, " YOASOBI - 夜に駆ける ");
        String text = new String(body, StandardCharsets.UTF_8);
        assertTrue(text.contains("--xyz\r\nContent-Disposition: form-data; name=\"prompt\"\r\n\r\nYOASOBI - 夜に駆ける\r\n"));
        assertTrue(text.contains("name=\"language\"\r\n\r\nauto\r\n"));
        String blank = new String(AsrClient.multipart("xyz", new byte[]{1}, null, "   "), StandardCharsets.UTF_8);
        assertFalse(blank.contains("name=\"prompt\""));
    }
}
