package kr.kro.backas.music.source;

import com.sedmelluq.discord.lavaplayer.tools.JsonBrowser;
import dev.lavalink.youtube.track.format.StreamFormat;
import org.apache.http.entity.ContentType;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SourceQualityTest {

    private static StreamFormat format(String mime, int itag, long contentLength) {
        return new StreamFormat(ContentType.parse(mime), itag, 999_000, contentLength, 2,
                "https://example.invalid/videoplayback?itag=" + itag, null, null, null, true, false);
    }

    private static SourceQuality.Details details(String json, boolean live) throws IOException {
        return SourceQuality.Details.from(JsonBrowser.parse(json), live);
    }

    @Test
    void opusShowsAverageBitrateAndSampleRate() throws IOException {
        SourceQuality quality = SourceQuality.of(format("audio/webm; codecs=\"opus\"", 251, 3_543_210),
                details("{\"averageBitrate\":128620,\"audioSampleRate\":\"48000\",\"approxDurationMs\":\"220481\"}", false));
        assertEquals("Opus 129kbps · 48kHz", quality.label());
    }

    @Test
    void aacKeepsItsOwnSampleRate() throws IOException {
        SourceQuality quality = SourceQuality.of(format("audio/mp4; codecs=\"mp4a.40.2\"", 140, 3_560_000),
                details("{\"averageBitrate\":129502,\"audioSampleRate\":\"44100\"}", false));
        assertEquals("AAC 130kbps · 44.1kHz", quality.label());
    }

    @Test
    void vorbisIsNamed() throws IOException {
        SourceQuality quality = SourceQuality.of(format("audio/webm; codecs=\"vorbis\"", 171, 0),
                details("{\"averageBitrate\":117000,\"audioSampleRate\":\"44100\"}", false));
        assertEquals("Vorbis 117kbps · 44.1kHz", quality.label());
    }

    @Test
    void bitrateFallsBackToSizeOverLength() throws IOException {
        SourceQuality quality = SourceQuality.of(format("audio/webm; codecs=\"opus\"", 251, 3_543_210),
                details("{\"audioSampleRate\":\"48000\",\"approxDurationMs\":\"220481\"}", false));
        assertEquals("Opus 129kbps · 48kHz", quality.label());
    }

    @Test
    void opusIsAlways48kHzEvenWithoutTheField() throws IOException {
        SourceQuality quality = SourceQuality.of(format("audio/webm; codecs=\"opus\"", 251, 0),
                details("{\"averageBitrate\":160000}", false));
        assertEquals("Opus 160kbps · 48kHz", quality.label());
    }

    @Test
    void aacWithoutSampleRateIsNotGuessed() throws IOException {
        SourceQuality quality = SourceQuality.of(format("audio/mp4; codecs=\"mp4a.40.2\"", 140, 0),
                details("{\"averageBitrate\":128000}", false));
        assertEquals("AAC 128kbps", quality.label());
    }

    @Test
    void muxedVideoStreamHidesTheBitrate() throws IOException {
        SourceQuality quality = SourceQuality.of(format("video/mp4; codecs=\"avc1.42001E, mp4a.40.2\"", 18, 20_000_000),
                details("{\"averageBitrate\":700000,\"audioSampleRate\":\"44100\"}", false));
        assertEquals("AAC · 44.1kHz", quality.label());
    }

    @Test
    void liveStreamHidesTheBitrate() throws IOException {
        SourceQuality quality = SourceQuality.of(format("audio/webm; codecs=\"opus\"", 251, 0),
                details("{\"bitrate\":150000,\"audioSampleRate\":\"48000\"}", true));
        assertEquals("Opus · 48kHz · 라이브", quality.label());
    }

    @Test
    void missingDetailsStillNamesTheCodec() {
        assertEquals("Opus · 48kHz", SourceQuality.of(format("audio/webm; codecs=\"opus\"", 251, 0), null).label());
        assertEquals("AAC", SourceQuality.of(format("audio/mp4; codecs=\"mp4a.40.2\"", 140, 0), null).label());
    }

    @Test
    void kilohertzKeepsOnlyTheNeededDigits() {
        assertEquals("48kHz", SourceQuality.kilohertz(48000));
        assertEquals("44.1kHz", SourceQuality.kilohertz(44100));
        assertEquals("22.05kHz", SourceQuality.kilohertz(22050));
    }
}
