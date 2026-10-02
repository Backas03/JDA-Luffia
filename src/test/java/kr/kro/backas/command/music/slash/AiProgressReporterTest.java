package kr.kro.backas.command.music.slash;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AiProgressReporterTest {

    @Test
    void hidesTheGpuSummaryUnlessDebugProvidesIt() {
        assertEquals("12.50% (54/432) · 40초 경과", AiProgressReporter.computeLine(null, 54, 432, 40));
        assertEquals("NVIDIA GeForce RTX 5080 | 190 token/s\nAMD Radeon RX 7800 XT | 189 token/s | 12.50% (54/432) · 40초 경과",
                AiProgressReporter.computeLine("NVIDIA GeForce RTX 5080 | 190 token/s\nAMD Radeon RX 7800 XT | 189 token/s", 54, 432, 40));
    }

    @Test
    void alwaysShowsElapsedTimeSoEveryRefreshChanges() {
        assertEquals("10초 경과", AiProgressReporter.computeLine(null, -1, -1, 10));
        assertEquals("0.00% (0/432) · 20초 경과", AiProgressReporter.computeLine(null, 0, 432, 20));
        assertEquals("0.00% (0/432) · 30초 경과", AiProgressReporter.computeLine("", 0, 432, 30));
    }
}
