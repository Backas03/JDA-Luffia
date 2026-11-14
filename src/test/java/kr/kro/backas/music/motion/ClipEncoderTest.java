package kr.kro.backas.music.motion;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClipEncoderTest {

    private static final ClipEncoder.Profile PROFILE = new ClipEncoder.Profile(480, 270, 12, 60);

    @Test
    void anythingButGifIsWebp() {
        assertEquals(ClipEncoder.Format.GIF, ClipEncoder.Format.of(" GIF "));
        assertEquals(ClipEncoder.Format.WEBP, ClipEncoder.Format.of("webp"));
        assertEquals(ClipEncoder.Format.WEBP, ClipEncoder.Format.of(null));
        assertEquals(ClipEncoder.Format.WEBP, ClipEncoder.Format.of("mp4"));
    }

    @Test
    void webpKeepsTheStillBannerSizeEvenWhenItHasToShrinkTheFile() {
        for (ClipEncoder.Profile profile : ClipEncoder.Format.WEBP.profiles) {
            assertEquals(960, profile.width());
            assertEquals(540, profile.height());
        }
    }

    @Test
    void seeksBeforeOpeningARemoteStream() {
        List<String> command = ClipEncoder.command("ffmpeg", ClipEncoder.Format.GIF, PROFILE,
                "https://example.invalid/videoplayback", List.of(63_250L), 6_000, true, Path.of("out.gif"));
        assertEquals("ffmpeg", command.get(0));
        assertTrue(command.indexOf("-ss") < command.indexOf("-i"));
        assertEquals("63.250", command.get(command.indexOf("-ss") + 1));
        assertEquals("6.000", command.get(command.indexOf("-t") + 1));
        assertTrue(command.contains("-rw_timeout"));
        assertTrue(command.contains("-an"));
        assertEquals(List.of("-f", "gif", "out.gif"), command.subList(command.size() - 3, command.size()));
    }

    @Test
    void everySceneIsItsOwnSeekIntoTheSameStream() {
        List<String> command = ClipEncoder.command("ffmpeg", ClipEncoder.Format.WEBP, PROFILE,
                "https://example.invalid/videoplayback", List.of(30_000L, 90_000L, 150_000L), 3_000, true, Path.of("out.webp"));
        assertEquals(3, Collections.frequency(command, "-i"));
        assertEquals(3, Collections.frequency(command, "-ss"));
        assertEquals(3, Collections.frequency(command, "3.000"));
        assertTrue(command.contains("150.000"));
        String graph = command.get(command.indexOf("-filter_complex") + 1);
        assertTrue(graph.startsWith("[0:v]freezedetect=n=0.003:d=2.400[s0];[1:v]freezedetect=n=0.003:d=2.400[s1];"
                + "[2:v]freezedetect=n=0.003:d=2.400[s2];[s0][s1][s2]concat=n=3:v=1:a=0,fps=12,"));
        assertTrue(ClipEncoder.graph(ClipEncoder.Format.WEBP, PROFILE, 3, 3_000, false).startsWith("[0:v][1:v][2:v]concat=n=3:v=1:a=0,fps=12,"));
    }

    @Test
    void aVideoIsStillOnlyWhenEverySceneStandsStill() {
        String frozen = "[freezedetect @ 0x1] lavfi.freezedetect.freeze_start: 0 | ";
        assertEquals(3, ClipEncoder.stillScenes(frozen + frozen + "frame= 10 | " + frozen));
        assertEquals(1, ClipEncoder.stillScenes("Input #0 | " + frozen));
        assertEquals(0, ClipEncoder.stillScenes("Input #0"));
    }

    @Test
    void aLoopFromTheStartNeedsNoSeekAndALocalFileNoNetworkTimeout() {
        List<String> command = ClipEncoder.command("ffmpeg", ClipEncoder.Format.WEBP, PROFILE, "clip.mp4", List.of(0L), 8_000, false, Path.of("out.webp"));
        assertFalse(command.contains("-ss"));
        assertFalse(command.contains("-rw_timeout"));
        assertEquals("60", command.get(command.indexOf("-q:v") + 1));
        assertEquals(List.of("-f", "webp", "out.webp"), command.subList(command.size() - 3, command.size()));
    }

    @Test
    void gifGetsItsOwnPaletteAndStillDetectionIsOptional() {
        String gif = ClipEncoder.graph(ClipEncoder.Format.GIF, PROFILE, 1, 6_000, true);
        assertTrue(gif.startsWith("[0:v]freezedetect=n=0.003:d=4.800,fps=12,"));
        assertTrue(gif.contains("scale=480:270:force_original_aspect_ratio=decrease"));
        assertTrue(gif.endsWith("paletteuse=dither=bayer:bayer_scale=4:diff_mode=rectangle"));
        String webp = ClipEncoder.graph(ClipEncoder.Format.WEBP, PROFILE, 1, 6_000, false);
        assertTrue(webp.startsWith("[0:v]fps=12,"));
        assertFalse(webp.contains("palette"));
        assertTrue(webp.endsWith("overlay=(W-w)/2:(H-h)/2"));
    }

    @Test
    void aFailureReportNeverCarriesTheSignedStreamAddress() {
        String input = "https://rr1.googlevideo.com/videoplayback?expire=1&sig=" + "A".repeat(900);
        String report = "Input #0 from '" + input + "'\n[https @ 0x1] HTTP error 403 Forbidden\n"
                + "[in#0 @ 0x2] Error opening input: Server returned 403 Forbidden (access denied)\nError opening input file " + input + ".\n"
                + "see https://ffmpeg.org/help for more";
        String tail = ClipEncoder.tail(report, input);
        assertFalse(tail.contains("googlevideo"));
        assertFalse(tail.contains("AAAA"));
        assertTrue(tail.contains("403 Forbidden"));
        assertTrue(tail.contains("<input>"));
        assertTrue(tail.endsWith("see <url> for more"));
    }

    @Test
    void aMissingFfmpegTurnsTheEncoderOffInsteadOfFailingEveryTrack() throws Exception {
        ClipEncoder encoder = new ClipEncoder("luffia-no-such-ffmpeg-binary", ClipEncoder.Format.GIF);
        assertTrue(encoder.isAvailable());
        ClipEncoder.Result result = encoder.encode("clip.mp4", List.of(0L), 6_000, false);
        assertEquals(ClipEncoder.Result.failed(ClipEncoder.MISSING_NOTE), result);
        assertFalse(encoder.isAvailable());
        assertEquals(ClipEncoder.Result.failed(ClipEncoder.MISSING_NOTE), encoder.encode("clip.mp4", List.of(0L), 6_000, false));
    }
}
