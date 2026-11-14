package kr.kro.backas.music.motion;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClipEncoderTest {

    private static final ClipEncoder.Profile PROFILE = new ClipEncoder.Profile(480, 270, 12);

    @Test
    void anythingButWebpFallsBackToGif() {
        assertEquals(ClipEncoder.Format.WEBP, ClipEncoder.Format.of(" WebP "));
        assertEquals(ClipEncoder.Format.GIF, ClipEncoder.Format.of("gif"));
        assertEquals(ClipEncoder.Format.GIF, ClipEncoder.Format.of(null));
        assertEquals(ClipEncoder.Format.GIF, ClipEncoder.Format.of("mp4"));
    }

    @Test
    void seeksBeforeOpeningARemoteStream() {
        List<String> command = ClipEncoder.command("ffmpeg", ClipEncoder.Format.GIF, PROFILE,
                "https://example.invalid/videoplayback", 63_250, 6_000, true, Path.of("out.gif"));
        assertEquals("ffmpeg", command.get(0));
        assertTrue(command.indexOf("-ss") < command.indexOf("-i"));
        assertEquals("63.250", command.get(command.indexOf("-ss") + 1));
        assertEquals("6.000", command.get(command.indexOf("-t") + 1));
        assertTrue(command.contains("-rw_timeout"));
        assertTrue(command.contains("-an"));
        assertEquals(List.of("-f", "gif", "out.gif"), command.subList(command.size() - 3, command.size()));
    }

    @Test
    void aLoopFromTheStartNeedsNoSeekAndALocalFileNoNetworkTimeout() {
        List<String> command = ClipEncoder.command("ffmpeg", ClipEncoder.Format.WEBP, PROFILE, "clip.mp4", 0, 8_000, false, Path.of("out.webp"));
        assertFalse(command.contains("-ss"));
        assertFalse(command.contains("-rw_timeout"));
        assertTrue(command.contains("libwebp_anim"));
        assertEquals(List.of("-f", "webp", "out.webp"), command.subList(command.size() - 3, command.size()));
    }

    @Test
    void gifGetsItsOwnPaletteAndStillDetectionIsOptional() {
        String gif = ClipEncoder.graph(ClipEncoder.Format.GIF, PROFILE, 6_000, true);
        assertTrue(gif.startsWith("[0:v]freezedetect=n=0.003:d=4.800,fps=12,"));
        assertTrue(gif.contains("scale=480:270:force_original_aspect_ratio=decrease"));
        assertTrue(gif.endsWith("paletteuse=dither=bayer:bayer_scale=4:diff_mode=rectangle"));
        String webp = ClipEncoder.graph(ClipEncoder.Format.WEBP, PROFILE, 6_000, false);
        assertTrue(webp.startsWith("[0:v]fps=12,"));
        assertFalse(webp.contains("palette"));
        assertTrue(webp.endsWith("overlay=(W-w)/2:(H-h)/2"));
    }

    @Test
    void aMissingFfmpegTurnsTheEncoderOffInsteadOfFailingEveryTrack() throws Exception {
        ClipEncoder encoder = new ClipEncoder("luffia-no-such-ffmpeg-binary", ClipEncoder.Format.GIF);
        assertTrue(encoder.isAvailable());
        ClipEncoder.Result result = encoder.encode("clip.mp4", 0, 6_000, false);
        assertEquals(ClipEncoder.Result.FAILED, result);
        assertFalse(encoder.isAvailable());
    }
}
