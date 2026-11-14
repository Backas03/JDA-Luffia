package kr.kro.backas.music.motion;

import dev.lavalink.youtube.track.format.StreamFormat;
import org.apache.http.entity.ContentType;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class YoutubeVideoStreamsTest {

    private static StreamFormat format(String mime, int itag, @Nullable String url) {
        return new StreamFormat(ContentType.parse(mime), itag, 500_000, 1_000_000, 0, url, null, null, null, true, false);
    }

    private static StreamFormat video(int itag) {
        return format("video/mp4; codecs=\"avc1.4D401E\"", itag, "https://example.invalid/videoplayback?itag=" + itag);
    }

    @Test
    void prefersTheSmallH264StreamOverBiggerOnes() {
        assertEquals(134, YoutubeVideoStreams.pick(List.of(video(137), video(136), video(135), video(134), video(133))).getItag());
        assertEquals(135, YoutubeVideoStreams.pick(List.of(video(137), video(136), video(135), video(133))).getItag());
    }

    @Test
    void ignoresAudioAndStreamsWithoutADirectAddress() {
        StreamFormat audio = format("audio/webm; codecs=\"opus\"", 251, "https://example.invalid/videoplayback?itag=251");
        StreamFormat sabr = format("video/mp4; codecs=\"avc1.4D401E\"", 134, null);
        assertEquals(18, YoutubeVideoStreams.pick(List.of(audio, sabr, video(18))).getItag());
        assertNull(YoutubeVideoStreams.pick(List.of(audio, sabr)));
        assertNull(YoutubeVideoStreams.pick(List.of()));
    }
}
