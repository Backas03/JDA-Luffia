package kr.kro.backas.music.lyrics.sync;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WhisperClientTest {

    @Test
    void parsesEndpointListWithOptionalLabels() {
        List<WhisperClient.Endpoint> endpoints = WhisperClient.parse("http://a:8000/|GPU A, http://b.example:8000 ,, ");
        assertEquals(2, endpoints.size());
        assertEquals("http://a:8000", endpoints.get(0).baseUrl());
        assertEquals("GPU A", endpoints.get(0).label());
        assertEquals("b.example", endpoints.get(1).label());
        assertTrue(WhisperClient.parse(null).isEmpty());
    }

    @Test
    void unconfiguredClientIsNeverAvailable() {
        WhisperClient client = new WhisperClient("");
        assertFalse(client.isConfigured());
        assertFalse(client.isAvailable());
        assertTrue(new WhisperClient("http://localhost:1").isAvailable());
    }

    @Test
    void parsesWordTimestampsFromTheServerResponse() throws Exception {
        String json = "{\"text\":\"hi there\",\"words\":[{\"word\":\" hi\",\"start\":0.5,\"end\":0.8},"
                + "{\"word\":\"there\",\"start\":0.9,\"end\":1.25},{\"word\":\"   \",\"start\":2,\"end\":2}]}";
        List<WhisperClient.Word> words = WhisperClient.parseWords(json);
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
        List<WhisperClient.Word> words = WhisperClient.parseWords(json);
        assertEquals(2, words.size());
        assertEquals("君", words.get(0).text());
        assertEquals(40_000, words.get(0).startMs());
        assertEquals("から", words.get(1).text());
        assertEquals(47_130, words.get(1).endMs());
    }

    @Test
    void postsToTheConfiguredPathOrTheOpenAiPath() {
        assertEquals("http://a:8000/v1/audio/transcriptions", WhisperClient.transcriptionUrl("http://a:8000"));
        assertEquals("http://b:8178/inference", WhisperClient.transcriptionUrl("http://b:8178/inference"));
        assertEquals("http://b:8178/inference", WhisperClient.transcriptionUrl(WhisperClient.parse("http://b:8178/inference/").get(0).baseUrl()));
    }

    @Test
    void multipartBodyCarriesTheWavAndTheLanguageHint() throws Exception {
        byte[] body = WhisperClient.multipart("xyz", new byte[]{1, 2, 3}, "ja");
        String text = new String(body, StandardCharsets.ISO_8859_1);
        assertTrue(text.startsWith("--xyz\r\nContent-Disposition: form-data; name=\"file\"; filename=\"audio.wav\""));
        assertTrue(text.contains("name=\"language\"\r\n\r\nja\r\n"));
        assertTrue(text.contains("name=\"response_format\"\r\n\r\nverbose_json\r\n"));
        assertTrue(text.endsWith("--xyz--\r\n"));
    }
}
