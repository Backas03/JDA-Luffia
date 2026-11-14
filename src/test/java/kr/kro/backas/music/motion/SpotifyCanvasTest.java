package kr.kro.backas.music.motion;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SpotifyCanvasTest {

    private static final String TRACK = "4PTG3Z6ehGkBFwjybzWkR8";
    private static final String CANVAS = "https://canvaz.scdn.co/upload/licensor/7JGwF0zhX9aOfPJzmDbGhw/video/0123456789abcdef.cnvs.mp4";

    @Test
    void theCacheRequestWrapsTheTrackUriInOneEntity() {
        byte[] uri = ("spotify:track:" + TRACK).getBytes(StandardCharsets.US_ASCII);
        ByteArrayOutputStream expected = new ByteArrayOutputStream();
        expected.write(0x0A);
        expected.write(38);
        expected.write(0x0A);
        expected.write(36);
        expected.writeBytes(uri);
        assertArrayEquals(expected.toByteArray(), SpotifyCanvas.cacheBody(TRACK));
    }

    @Test
    void aTokenFailureIsReportedAsALookupFailureNotACrash() {
        IOException failure = assertThrows(IOException.class, () -> SpotifyCanvas.find(() -> {
            throw new RuntimeException("Account token refreshing failed");
        }, TRACK));
        assertEquals("Account token refreshing failed", failure.getCause().getMessage());
    }

    @Test
    void findsTheVideoInsideABinaryAnswer() {
        ByteArrayOutputStream answer = new ByteArrayOutputStream();
        answer.writeBytes(new byte[]{0x0A, 0x7F, 0x0A, 0x20});
        answer.writeBytes("0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.US_ASCII));
        answer.writeBytes(new byte[]{0x12, 0x5E});
        answer.writeBytes(CANVAS.getBytes(StandardCharsets.US_ASCII));
        answer.writeBytes(new byte[]{0x1A, 0x10, 0x20, 0x01});
        assertEquals(CANVAS, SpotifyCanvas.videoUrl(answer.toByteArray()));
    }

    @Test
    void findsTheVideoInsideAJsonAnswer() {
        String json = "{\"data\":{\"trackUnion\":{\"canvas\":{\"type\":\"VIDEO_LOOPING\",\"url\":\"" + CANVAS.replace("/", "\\/") + "\"}}}}";
        assertEquals(CANVAS, SpotifyCanvas.videoUrl(json.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void aTrackWithoutACanvasOrWithAStillOneHasNoVideo() {
        assertNull(SpotifyCanvas.videoUrl("{\"data\":{\"trackUnion\":{\"canvas\":null}}}".getBytes(StandardCharsets.UTF_8)));
        assertNull(SpotifyCanvas.videoUrl("https://canvaz.scdn.co/upload/artist/abc/image/still.cnvs.jpg".getBytes(StandardCharsets.UTF_8)));
        assertNull(SpotifyCanvas.videoUrl(new byte[0]));
    }
}
