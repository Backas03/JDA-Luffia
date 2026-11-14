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
import java.net.URI;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class MotionBanners {

    public record Stored(@Nullable String file, @Nullable String source, long at) {
    }

    private record Attempt(@Nullable MotionClip clip, @Nullable String source, boolean settled) {
        static final Attempt NONE = new Attempt(null, null, true);
        static final Attempt RETRY = new Attempt(null, null, false);
    }

    static final String DIRECTORY = "motion-banner/";
    static final long MISS_RETRY_MS = 24L * 60 * 60 * 1000;
    static final int MAX_CLIPS = 100;
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
        this.sources = sources;
        this.cache = cache;
        this.encoder = new ClipEncoder(config == null ? null : config.ffmpeg(), ClipEncoder.Format.of(config == null ? null : config.format()));
    }

    static long clipMs(int seconds) {
        return Math.max(MIN_CLIP_SECONDS, Math.min(MAX_CLIP_SECONDS, seconds)) * 1000L;
    }

    public boolean isEnabled() {
        return enabled && encoder.isAvailable();
    }

    @Nullable
    public MotionClip cached(AudioTrack track) {
        if (!isEnabled()) return null;
        String key = key(track);
        return key == null ? null : read(key);
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
    private MotionClip read(String key) {
        Stored stored = cache.read(DIRECTORY + key + ".json", Stored.class);
        if (stored == null || stored.file() == null) return null;
        byte[] data = cache.readBytes(DIRECTORY + stored.file());
        if (data == null || data.length == 0) return null;
        int dot = stored.file().lastIndexOf('.');
        return new MotionClip(data, dot < 0 ? encoder.format().extension : stored.file().substring(dot + 1));
    }

    @Nullable
    private MotionClip load(AudioTrack track, String key) throws InterruptedException {
        MotionClip cached = read(key);
        if (cached != null) return cached;
        Stored stored = cache.read(DIRECTORY + key + ".json", Stored.class);
        long now = System.currentTimeMillis();
        if (stored != null && stored.file() == null && now - stored.at() < MISS_RETRY_MS) return null;
        Attempt canvas = canvas(track);
        Attempt chosen = canvas.clip() != null ? canvas : highlight(track);
        if (chosen.clip() == null) {
            if (canvas.settled() && chosen.settled()) cache.write(DIRECTORY + key + ".json", new Stored(null, null, now));
            return null;
        }
        String file = key + "." + chosen.clip().extension();
        cache.writeBytes(DIRECTORY + file, chosen.clip().data());
        cache.write(DIRECTORY + key + ".json", new Stored(file, chosen.source(), now));
        cache.prune(DIRECTORY, "." + chosen.clip().extension(), MAX_CLIPS);
        LOGGER.info("made a {} byte motion banner for {} from {}", chosen.clip().data().length, track.getInfo().title, chosen.source());
        return chosen.clip();
    }

    private Attempt canvas(AudioTrack track) throws InterruptedException {
        String trackId = spotifyId(track);
        SpotifySourceManager spotify = sources.getSpotify();
        if (trackId == null || !(spotify instanceof PatchedSpotifySourceManager patched)) return Attempt.NONE;
        String url;
        try {
            url = SpotifyCanvas.find(patched::webPlayerToken, trackId);
        } catch (IOException e) {
            LOGGER.info("spotify canvas lookup failed for {}: {}", track.getInfo().title, e.toString());
            return Attempt.RETRY;
        }
        if (url == null) {
            LOGGER.info("spotify has no canvas for {} (track {})", track.getInfo().title, trackId);
            return Attempt.NONE;
        }
        return encoded(encoder.encode(url, 0, CANVAS_MS, false), "Spotify Canvas");
    }

    private Attempt highlight(AudioTrack track) throws InterruptedException {
        YoutubeAudioSourceManager youtube = sources.getAudioPlayerManager().source(YoutubeAudioSourceManager.class);
        String videoId = awaitVideoId(track);
        if (youtube == null || videoId == null) return Attempt.RETRY;
        if (track.getDuration() > 0 && track.getDuration() < clipMs * 2) return Attempt.NONE;
        long start;
        try {
            start = YoutubeHighlights.find(videoId, clipMs);
        } catch (IOException e) {
            LOGGER.info("youtube most replayed lookup failed for {}: {}", videoId, e.toString());
            return Attempt.RETRY;
        }
        if (start == YoutubeHighlights.NONE) {
            LOGGER.info("youtube has no most replayed graph for {} (video {})", track.getInfo().title, videoId);
            return Attempt.NONE;
        }
        URI stream = YoutubeVideoStreams.find(youtube, videoId);
        if (stream == null) {
            LOGGER.info("no youtube client gave a video stream for {}", videoId);
            return Attempt.RETRY;
        }
        return encoded(encoder.encode(stream.toString(), start, clipMs, true), "YouTube " + start / 1000 + "s");
    }

    private Attempt encoded(ClipEncoder.Result result, String source) {
        if (result.still()) return Attempt.NONE;
        if (result.data() == null) return Attempt.RETRY;
        return new Attempt(new MotionClip(result.data(), encoder.format().extension), source, true);
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
