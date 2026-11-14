package kr.kro.backas.music.motion;

import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

final class ClipEncoder {

    enum Format {
        GIF("gif", List.of(new Profile(480, 270, 12), new Profile(360, 202, 10))),
        WEBP("webp", List.of(new Profile(640, 360, 15), new Profile(480, 270, 12)));

        final String extension;
        final List<Profile> profiles;

        Format(String extension, List<Profile> profiles) {
            this.extension = extension;
            this.profiles = profiles;
        }

        static Format of(@Nullable String name) {
            return name != null && name.trim().equalsIgnoreCase("webp") ? WEBP : GIF;
        }
    }

    record Profile(int width, int height, int fps) {
    }

    record Result(@Nullable byte[] data, boolean still) {
        static final Result FAILED = new Result(null, false);
        static final Result STILL = new Result(null, true);
    }

    static final int MAX_BYTES = 8 * 1024 * 1024;
    static final double STILL_RATIO = 0.8;
    private static final String STILL_MARK = "freeze_start";
    private static final long TIMEOUT_SECONDS = 45;
    private static final int LOG_TAIL = 400;
    private static final Logger LOGGER = LoggerFactory.getLogger(ClipEncoder.class);

    private final String ffmpeg;
    private final Format format;
    private final Semaphore slot = new Semaphore(1);
    private volatile boolean missing;

    ClipEncoder(@Nullable String ffmpeg, Format format) {
        this.ffmpeg = ffmpeg == null || ffmpeg.isBlank() ? "ffmpeg" : ffmpeg.trim();
        this.format = format;
    }

    boolean isAvailable() {
        return !missing;
    }

    Format format() {
        return format;
    }

    Result encode(String input, long startMs, long durationMs, boolean detectStill) throws InterruptedException {
        if (missing) return Result.FAILED;
        slot.acquire();
        try {
            for (Profile profile : format.profiles) {
                Result result = run(profile, input, startMs, durationMs, detectStill);
                if (result.still() || result.data() == null) return result;
                if (result.data().length <= MAX_BYTES) return result;
                LOGGER.info("motion banner came out at {} bytes with {}, trying a smaller one", result.data().length, profile);
            }
            return Result.FAILED;
        } finally {
            slot.release();
        }
    }

    private Result run(Profile profile, String input, long startMs, long durationMs, boolean detectStill) throws InterruptedException {
        Path output = null;
        Path log = null;
        Process process = null;
        try {
            output = Files.createTempFile("luffia-motion-", "." + format.extension);
            log = Files.createTempFile("luffia-motion-", ".log");
            process = start(command(ffmpeg, format, profile, input, startMs, durationMs, detectStill, output), log);
            if (process == null) return Result.FAILED;
            if (!process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                LOGGER.info("ffmpeg took longer than {}s on a motion banner, giving up", TIMEOUT_SECONDS);
                return Result.FAILED;
            }
            String report = Files.readString(log, StandardCharsets.ISO_8859_1);
            if (detectStill && report.contains(STILL_MARK)) return Result.STILL;
            if (process.exitValue() != 0) {
                LOGGER.info("ffmpeg failed on a motion banner (exit {}): {}", process.exitValue(), tail(report));
                return Result.FAILED;
            }
            byte[] data = Files.readAllBytes(output);
            return data.length == 0 ? Result.FAILED : new Result(data, false);
        } catch (IOException e) {
            LOGGER.info("could not read the motion banner ffmpeg made: {}", e.toString());
            return Result.FAILED;
        } finally {
            if (process != null && process.isAlive()) process.destroyForcibly();
            delete(output);
            delete(log);
        }
    }

    @Nullable
    private Process start(List<String> command, Path log) {
        try {
            return new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        } catch (IOException e) {
            missing = true;
            LOGGER.warn("ffmpeg 를 실행할 수 없어 움직이는 배너를 끕니다. ffmpeg 를 설치하거나 music-player.motion-banner.ffmpeg 에 경로를 적어 주세요: {}", e.toString());
            return null;
        }
    }

    static List<String> command(String ffmpeg, Format format, Profile profile, String input, long startMs, long durationMs,
                                boolean detectStill, Path output) {
        List<String> command = new ArrayList<>(List.of(ffmpeg, "-hide_banner", "-nostdin", "-nostats", "-loglevel", "info", "-y"));
        if (input.startsWith("http")) command.addAll(List.of("-rw_timeout", "15000000"));
        if (startMs > 0) command.addAll(List.of("-ss", seconds(startMs)));
        command.addAll(List.of("-t", seconds(durationMs), "-i", input, "-an", "-filter_complex", graph(format, profile, durationMs, detectStill)));
        if (format == Format.WEBP) command.addAll(List.of("-c:v", "libwebp_anim", "-loop", "0", "-q:v", "60", "-compression_level", "3"));
        command.addAll(List.of("-f", format.extension, output.toString()));
        return command;
    }

    static String graph(Format format, Profile profile, long durationMs, boolean detectStill) {
        String size = profile.width() + ":" + profile.height();
        StringBuilder graph = new StringBuilder("[0:v]");
        if (detectStill) graph.append("freezedetect=n=0.003:d=").append(seconds((long) (durationMs * STILL_RATIO))).append(',');
        graph.append("fps=").append(profile.fps()).append(",split[a][b];")
                .append("[a]scale=32:18:force_original_aspect_ratio=increase,crop=32:18,scale=").append(size)
                .append(":flags=bilinear,colorchannelmixer=rr=0.45:gg=0.45:bb=0.45[bg];")
                .append("[b]scale=").append(size).append(":force_original_aspect_ratio=decrease[fg];")
                .append("[bg][fg]overlay=(W-w)/2:(H-h)/2");
        if (format == Format.GIF) {
            graph.append(",split[p][q];[p]palettegen=stats_mode=diff[pal];[q][pal]paletteuse=dither=bayer:bayer_scale=4:diff_mode=rectangle");
        }
        return graph.toString();
    }

    private static String seconds(long millis) {
        return String.format(Locale.ROOT, "%.3f", millis / 1000.0);
    }

    private static String tail(String report) {
        String trimmed = report.strip();
        return trimmed.length() <= LOG_TAIL ? trimmed : trimmed.substring(trimmed.length() - LOG_TAIL);
    }

    private static void delete(@Nullable Path path) {
        if (path == null) return;
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            LOGGER.debug("could not delete {}: {}", path, e.toString());
        }
    }
}
