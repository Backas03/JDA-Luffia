package kr.kro.backas.music.motion;

import com.github.topi314.lavasrc.spotify.SpotifyAudioTrack;
import com.github.topi314.lavasrc.spotify.SpotifySourceManager;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import dev.lavalink.youtube.YoutubeAudioSourceManager;
import kr.kro.backas.config.LuffiaConfig;
import kr.kro.backas.music.cache.DiskCache;
import kr.kro.backas.music.lyrics.TranslationJobs;
import kr.kro.backas.music.source.MusicSourceRegistry;
import kr.kro.backas.music.source.PatchedSpotifySourceManager;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class MotionBanners {

    public record Stored(@Nullable String file, @Nullable String source, long at, int version, boolean canvasOpen) {
    }

    private record Attempt(@Nullable MotionClip clip, boolean settled, String note) {
        static Attempt none(String note) {
            return new Attempt(null, true, note);
        }

        static Attempt retry(String note) {
            return new Attempt(null, false, note);
        }
    }

    static final String DIRECTORY = "motion-banner/";
    static final long MISS_RETRY_MS = 24L * 60 * 60 * 1000;
    static final int MAX_CLIPS = 100;
    static final int VERSION = 5;
    static final int MAX_SCENES = 5;
    static final long MIN_SCENE_MS = 1_500;
    static final int MAX_STREAM_CLIENTS = 3;
    static final long CANVAS_MS = 8_000;
    static final int MIN_CLIP_SECONDS = 2;
    static final int MAX_CLIP_SECONDS = 10;
    private static final long MIRROR_WAIT_MS = 15_000;
    private static final long MIRROR_POLL_MS = 500;
    private static final Pattern SPOTIFY_TRACK_URL = Pattern.compile("open\\.spotify\\.com/(?:intl-[a-z]+/)?track/([A-Za-z0-9]+)");
    private static final Pattern YOUTUBE_ID = Pattern.compile("[A-Za-z0-9_-]{11}");
    private static final Logger LOGGER = LoggerFactory.getLogger(MotionBanners.class);

    private final boolean enabled;
    private final long clipMs;
    private final int scenes;
    private final long sceneMs;
    private final MusicSourceRegistry sources;
    private final DiskCache cache;
    private final ClipEncoder encoder;
    private final Map<String, CompletableFuture<MotionClip>> running = new ConcurrentHashMap<>();

    public MotionBanners(@Nullable LuffiaConfig.MotionBanner config, MusicSourceRegistry sources) {
        this(config, sources, DiskCache.defaultCache());
    }

    MotionBanners(@Nullable LuffiaConfig.MotionBanner config, MusicSourceRegistry sources, DiskCache cache) {
        this.enabled = config != null && config.enabled();
        this.clipMs = clipMs(config == null ? 0 : config.clipSeconds());
        this.scenes = scenes(config == null ? 1 : config.scenes(), clipMs);
        this.sceneMs = clipMs / scenes;
        this.sources = sources;
        this.cache = cache;
        this.encoder = new ClipEncoder(config == null ? null : config.ffmpeg(), ClipEncoder.Format.of(config == null ? null : config.format()));
        if (enabled) TranslationJobs.EXECUTOR.execute(encoder::probe);
    }

    static long clipMs(int seconds) {
        return Math.max(MIN_CLIP_SECONDS, Math.min(MAX_CLIP_SECONDS, seconds)) * 1000L;
    }

    static int scenes(int wanted, long clipMs) {
        return (int) Math.max(1, Math.min(Math.min(MAX_SCENES, wanted), clipMs / MIN_SCENE_MS));
    }

    public boolean isEnabled() {
        return enabled && encoder.isAvailable();
    }

    @Nullable
    public MotionClip cached(AudioTrack track) {
        if (!isEnabled()) return null;
        String key = key(track);
        Stored stored = key == null ? null : stored(key);
        return stored == null || stored.canvasOpen() ? null : clip(stored);
    }

    public CompletableFuture<MotionClip> prepare(AudioTrack track) {
        if (!isEnabled() || track.getInfo().isStream) return CompletableFuture.completedFuture(null);
        String key = key(track);
        if (key == null) return CompletableFuture.completedFuture(null);
        CompletableFuture<MotionClip> created = new CompletableFuture<>();
        CompletableFuture<MotionClip> existing = running.putIfAbsent(key, created);
        if (existing != null) return existing;
        TranslationJobs.EXECUTOR.execute(() -> {
            try {
                created.complete(load(track, key));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                created.complete(null);
            } catch (RuntimeException e) {
                LOGGER.warn("motion banner lookup failed for {}", track.getInfo().title, e);
                created.complete(null);
            } finally {
                running.remove(key, created);
            }
        });
        return created;
    }

    @Nullable
    private Stored stored(String key) {
        Stored stored = cache.read(DIRECTORY + key + ".json", Stored.class);
        return stored == null || stored.version() != VERSION ? null : stored;
    }

    @Nullable
    private MotionClip clip(Stored stored) {
        String extension = encoder.format().extension;
        if (stored.file() == null || !stored.file().endsWith("." + extension)) return null;
        byte[] data = cache.readBytes(DIRECTORY + stored.file());
        return data == null || data.length == 0 ? null : new MotionClip(data, extension);
    }

    @Nullable
    private MotionClip load(AudioTrack track, String key) throws InterruptedException {
        Stored stored = stored(key);
        MotionClip kept = stored == null ? null : clip(stored);
        long now = System.currentTimeMillis();
        if (kept != null && !stored.canvasOpen()) return kept;
        if (kept == null && stored != null && stored.file() == null && now - stored.at() < MISS_RETRY_MS) return null;
        Attempt canvas = canvas(track);
        if (kept != null && canvas.clip() == null) {
            if (canvas.settled()) cache.write(DIRECTORY + key + ".json", new Stored(stored.file(), stored.source(), stored.at(), VERSION, false));
            LOGGER.info("kept the motion banner from {} for {}: spotify canvas: {}", stored.source(), track.getInfo().title, canvas.note());
            return kept;
        }
        Attempt chosen = canvas.clip() != null ? canvas : highlight(track);
        if (chosen.clip() == null) {
            boolean settled = canvas.settled() && chosen.settled();
            if (settled) cache.write(DIRECTORY + key + ".json", new Stored(null, null, now, VERSION, false));
            LOGGER.info("no motion banner for {} ({}): spotify canvas: {}; youtube: {}", track.getInfo().title,
                    settled ? "asking again in a day" : "asking again next time", canvas.note(), chosen.note());
            return null;
        }
        String file = key + "." + chosen.clip().extension();
        cache.writeBytes(DIRECTORY + file, chosen.clip().data());
        cache.write(DIRECTORY + key + ".json", new Stored(file, chosen.note(), now, VERSION, chosen != canvas && !canvas.settled()));
        for (ClipEncoder.Format format : ClipEncoder.Format.values()) {
            cache.prune(DIRECTORY, "." + format.extension, format == encoder.format() ? MAX_CLIPS : 0);
        }
        LOGGER.info("made a {} byte motion banner for {} from {}", chosen.clip().data().length, track.getInfo().title, chosen.note());
        return chosen.clip();
    }

    private Attempt canvas(AudioTrack track) throws InterruptedException {
        String trackId = spotifyId(track);
        SpotifySourceManager spotify = sources.getSpotify();
        if (trackId == null) return Attempt.none("not a spotify track");
        if (!(spotify instanceof PatchedSpotifySourceManager patched)) return Attempt.none("spotify is not set up");
        String url;
        try {
            url = SpotifyCanvas.find(patched::webPlayerToken, trackId);
        } catch (IOException e) {
            return Attempt.retry("lookup failed for track " + trackId + ": " + describe(e));
        }
        if (url == null) return Attempt.none("track " + trackId + " has none");
        return encoded(encoder.encode(url, List.of(0L), CANVAS_MS, false), "Spotify Canvas");
    }

    private Attempt highlight(AudioTrack track) throws InterruptedException {
        YoutubeAudioSourceManager youtube = sources.getAudioPlayerManager().source(YoutubeAudioSourceManager.class);
        String videoId = awaitVideoId(track);
        if (youtube == null || videoId == null) return Attempt.retry("the youtube video behind this track is not known yet");
        long durationMs = track.getDuration();
        if (durationMs > 0 && durationMs < clipMs * 2) return Attempt.none("video " + videoId + " is too short");
        List<Long> starts;
        try {
            starts = YoutubeHighlights.find(videoId, sceneMs, scenes);
        } catch (IOException e) {
            return Attempt.retry("most replayed lookup failed for video " + videoId + ": " + describe(e));
        }
        if (starts.isEmpty()) return Attempt.none("video " + videoId + " has no most replayed graph");
        StringBuilder source = new StringBuilder("YouTube video ").append(videoId).append(" at");
        for (long start : starts) source.append(' ').append(start / 1000).append('s');
        Set<String> tried = new HashSet<>();
        StringBuilder failures = new StringBuilder();
        while (tried.size() < MAX_STREAM_CLIENTS) {
            YoutubeVideoStreams.Stream stream = YoutubeVideoStreams.find(youtube, videoId, tried);
            if (stream == null) break;
            tried.add(stream.client());
            Attempt attempt = encoded(encoder.encode(stream.uri().toString(), starts, sceneMs, true), source + " via " + stream.client());
            if (attempt.clip() != null || attempt.settled()) return attempt;
            if (failures.length() > 0) failures.append("; ");
            failures.append(attempt.note());
        }
        return Attempt.retry(failures.length() > 0 ? failures.toString() : "no client gave a video stream for video " + videoId);
    }

    private Attempt encoded(ClipEncoder.Result result, String source) {
        if (result.still()) return Attempt.none(source + ": " + result.note());
        if (result.data() == null) return Attempt.retry(source + ": " + result.note());
        return new Attempt(new MotionClip(result.data(), encoder.format().extension), true, source);
    }

    @Nullable
    private String awaitVideoId(AudioTrack track) throws InterruptedException {
        long deadline = System.currentTimeMillis() + MIRROR_WAIT_MS;
        while (true) {
            String videoId = sources.getSourceQualities().videoIdOf(track);
            if (videoId != null && YOUTUBE_ID.matcher(videoId).matches()) return videoId;
            if (spotifyId(track) == null || System.currentTimeMillis() >= deadline) return null;
            Thread.sleep(MIRROR_POLL_MS);
        }
    }

    static String describe(Throwable error) {
        StringBuilder text = new StringBuilder(chain(error));
        for (Throwable other : error.getSuppressed()) text.append(" (before that: ").append(chain(other)).append(')');
        return text.toString();
    }

    private static String chain(Throwable error) {
        StringBuilder text = new StringBuilder(error.toString());
        Throwable cause = error.getCause();
        for (int depth = 0; cause != null && depth < 3; depth++) {
            text.append(" <- ").append(cause);
            cause = cause.getCause();
        }
        return text.toString();
    }

    @Nullable
    static String key(AudioTrack track) {
        String spotify = spotifyId(track);
        if (spotify != null) return "spotify-" + DiskCache.safeName(spotify);
        String id = track.getIdentifier();
        boolean youtube = track.getSourceManager() != null && "youtube".equals(track.getSourceManager().getSourceName());
        return youtube && id != null && YOUTUBE_ID.matcher(id).matches() ? "youtube-" + id : null;
    }

    @Nullable
    static String spotifyId(AudioTrack track) {
        if (track instanceof SpotifyAudioTrack) return track.getIdentifier();
        String uri = track.getInfo().uri;
        if (uri == null) return null;
        Matcher matcher = SPOTIFY_TRACK_URL.matcher(uri);
        return matcher.find() ? matcher.group(1) : null;
    }
}
