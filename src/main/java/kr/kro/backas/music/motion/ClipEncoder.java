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
import java.util.regex.Pattern;

final class ClipEncoder {

    enum Format {
        GIF("gif", List.of(new Profile(480, 270, 12, 0), new Profile(360, 202, 10, 0))),
        WEBP("webp", List.of(new Profile(960, 540, 15, 60), new Profile(960, 540, 12, 35)));

        final String extension;
        final List<Profile> profiles;

        Format(String extension, List<Profile> profiles) {
            this.extension = extension;
            this.profiles = profiles;
        }

        static Format of(@Nullable String name) {
            return name != null && name.trim().equalsIgnoreCase("gif") ? GIF : WEBP;
        }
    }

    record Profile(int width, int height, int fps, int quality) {
    }

    record Result(@Nullable byte[] data, boolean still, String note) {
        static Result failed(String note) {
            return new Result(null, false, note);
        }
    }

    static final int MAX_BYTES = 8 * 1024 * 1024;
    static final double STILL_RATIO = 0.8;
    static final String MISSING_NOTE = "ffmpeg is not installed";
    private static final String STILL_MARK = "freeze_start";
    private static final Pattern URL = Pattern.compile("https?://[^\\s|]+");
    private static final long TIMEOUT_SECONDS = 45;
    private static final long PROBE_SECONDS = 10;
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

    void probe() {
        Path log = null;
        Process process = null;
        try {
            log = Files.createTempFile("luffia-motion-", ".log");
            process = start(List.of(ffmpeg, "-hide_banner", "-version"), log);
            if (process == null) return;
            if (!process.waitFor(PROBE_SECONDS, TimeUnit.SECONDS)) {
                LOGGER.warn("{} -version 이 {}초 안에 끝나지 않았습니다. 움직이는 배너가 동작하지 않을 수 있습니다.", ffmpeg, PROBE_SECONDS);
                return;
            }
            String version = Files.readString(log, StandardCharsets.ISO_8859_1).lines().findFirst().orElse("").strip();
            LOGGER.info("움직이는 배너를 {} 형식으로 만듭니다 ({})", format.extension, version.isEmpty() ? ffmpeg : version);
        } catch (IOException e) {
            LOGGER.warn("ffmpeg 를 확인하지 못했습니다: {}", e.toString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            if (process != null && process.isAlive()) process.destroyForcibly();
            delete(log);
        }
    }

    Result encode(String input, List<Long> starts, long sceneMs, boolean detectStill) throws InterruptedException {
        if (missing) return Result.failed(MISSING_NOTE);
        slot.acquire();
        try {
            int size = 0;
            for (Profile profile : format.profiles) {
                Result result = run(profile, input, starts, sceneMs, detectStill);
                if (result.still() || result.data() == null) return result;
                if (result.data().length <= MAX_BYTES) return result;
                size = result.data().length;
            }
            return Result.failed("even the smallest clip was " + size + " bytes");
        } finally {
            slot.release();
        }
    }

    private Result run(Profile profile, String input, List<Long> starts, long sceneMs, boolean detectStill) throws InterruptedException {
        Path output = null;
        Path log = null;
        Process process = null;
        try {
            output = Files.createTempFile("luffia-motion-", "." + format.extension);
            log = Files.createTempFile("luffia-motion-", ".log");
            process = start(command(ffmpeg, format, profile, input, starts, sceneMs, detectStill, output), log);
            if (process == null) return Result.failed(MISSING_NOTE);
            if (!process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) return Result.failed("ffmpeg took longer than " + TIMEOUT_SECONDS + "s");
            String report = Files.readString(log, StandardCharsets.ISO_8859_1);
            if (detectStill && stillScenes(report) >= starts.size()) return new Result(null, true, "the picture does not move");
            if (process.exitValue() != 0) return Result.failed("ffmpeg exited with " + process.exitValue() + ": " + tail(report, input));
            byte[] data = Files.readAllBytes(output);
            return data.length == 0 ? Result.failed("ffmpeg wrote an empty file: " + tail(report, input)) : new Result(data, false, "ok");
        } catch (IOException e) {
            return Result.failed("could not read what ffmpeg made: " + e);
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
            LOGGER.warn("ffmpeg 를 실행할 수 없어 움직이는 배너를 끕니다. ffmpeg 를 설치하거나 music-player.motion-banner.ffmpeg 에 경로를 적은 뒤 재시작해 주세요: {}", e.toString());
            return null;
        }
    }

    static List<String> command(String ffmpeg, Format format, Profile profile, String input, List<Long> starts, long sceneMs,
                                boolean detectStill, Path output) {
        List<String> command = new ArrayList<>(List.of(ffmpeg, "-hide_banner", "-nostdin", "-nostats", "-loglevel", "info", "-y"));
        for (long startMs : starts) {
            if (input.startsWith("http")) command.addAll(List.of("-rw_timeout", "15000000"));
            if (startMs > 0) command.addAll(List.of("-ss", seconds(startMs)));
            command.addAll(List.of("-t", seconds(sceneMs), "-i", input));
        }
        command.addAll(List.of("-an", "-filter_complex", graph(format, profile, starts.size(), sceneMs, detectStill)));
        if (format == Format.WEBP) {
            command.addAll(List.of("-loop", "0", "-q:v", String.valueOf(profile.quality()), "-compression_level", "3"));
        }
        command.addAll(List.of("-f", format.extension, output.toString()));
        return command;
    }

    static int stillScenes(String report) {
        int count = 0;
        for (int at = report.indexOf(STILL_MARK); at >= 0; at = report.indexOf(STILL_MARK, at + STILL_MARK.length())) count++;
        return count;
    }

    static String graph(Format format, Profile profile, int scenes, long sceneMs, boolean detectStill) {
        String size = profile.width() + ":" + profile.height();
        String still = "freezedetect=n=0.003:d=" + seconds((long) (sceneMs * STILL_RATIO));
        StringBuilder graph = new StringBuilder();
        if (scenes > 1) {
            if (detectStill) {
                for (int i = 0; i < scenes; i++) graph.append('[').append(i).append(":v]").append(still).append("[s").append(i).append("];");
                for (int i = 0; i < scenes; i++) graph.append("[s").append(i).append(']');
            } else {
                for (int i = 0; i < scenes; i++) graph.append('[').append(i).append(":v]");
            }
            graph.append("concat=n=").append(scenes).append(":v=1:a=0,");
        } else {
            graph.append("[0:v]");
            if (detectStill) graph.append(still).append(',');
        }
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

    static String tail(String report, String input) {
        String trimmed = URL.matcher(report.replace(input, "<input>")).replaceAll("<url>").strip().replaceAll("\\s*\\R\\s*", " | ");
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
