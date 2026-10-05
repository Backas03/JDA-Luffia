package kr.kro.backas.music.llm;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LlmEndpointsTest {

    @Test
    void parsesTheEndpointSpecFormat() {
        List<LlmEndpoint> endpoints = LlmEndpoints.parse(
                "http://192.168.0.2:11434|NVIDIA GeForce RTX 5080|luffia,http://gpu-b.example:11434|AMD Radeon RX 7800 XT,"
                        + "http://127.0.0.1:8765|AMD Ryzen 5 5600G(6 core) 32GB");
        assertEquals(3, endpoints.size());
        assertEquals("NVIDIA GeForce RTX 5080", endpoints.get(0).label());
        assertEquals("luffia", endpoints.get(0).preferredModel());
        assertEquals(1, endpoints.get(0).slots());
        assertEquals("", endpoints.get(1).preferredModel());
        assertFalse(endpoints.get(0).isFallback());
        assertFalse(endpoints.get(1).isFallback());
        assertTrue(endpoints.get(2).isFallback());
    }

    @Test
    void readsSlotsAndClampsThem() {
        List<LlmEndpoint> endpoints = LlmEndpoints.parse("http://a|A|m|3,http://b|B|m|99,http://c|C|m|x");
        assertEquals(3, endpoints.get(0).slots());
        assertEquals(LlmEndpoints.MAX_SLOTS, endpoints.get(1).slots());
        assertEquals(1, endpoints.get(2).slots());
    }

    @Test
    void explicitFallbackMarkWins() {
        List<LlmEndpoint> endpoints = LlmEndpoints.parse("http://cpu|CPU||fallback,http://a|A|m|2,http://b|B|m|2");
        assertTrue(endpoints.get(0).isFallback());
        assertFalse(endpoints.get(1).isFallback());
        assertFalse(endpoints.get(2).isFallback());
    }

    @Test
    void singleServerIsNeverAFallbackAndLabelDefaultsToHost() {
        List<LlmEndpoint> endpoints = LlmEndpoints.parse("http://127.0.0.1:11434/");
        assertEquals(1, endpoints.size());
        assertFalse(endpoints.get(0).isFallback());
        assertEquals("127.0.0.1", endpoints.get(0).label());
        assertEquals("http://127.0.0.1:11434", endpoints.get(0).baseUrl());
    }
}
