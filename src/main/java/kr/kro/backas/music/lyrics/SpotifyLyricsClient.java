package kr.kro.backas.music.lyrics;

import com.github.topi314.lavalyrics.lyrics.AudioLyrics;
import com.github.topi314.lavasrc.spotify.SpotifyAudioTrack;
import com.github.topi314.lavasrc.spotify.SpotifySourceManager;
import com.sedmelluq.discord.lavaplayer.track.AudioItem;
import com.sedmelluq.discord.lavaplayer.track.AudioPlaylist;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import com.sedmelluq.discord.lavaplayer.track.AudioTrackInfo;
import kr.kro.backas.music.cache.DiskCache;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class SpotifyLyricsClient {

    public record Stored(@Nullable Lyrics lyrics, long at, int version) {
    }

    static final int VERSION = 2;
    static final int MAX_QUERIES = 3;
    static final long DURATION_TOLERANCE_MS = 5_000;
    static final long SAME_ARTIST_TOLERANCE_MS = 3_000;
    static final int SAME_ARTIST_RANKS = 3;
    private static final long WAIT_MS = 10_000;
    private static final long MISS_RETRY_MS = 24L * 60 * 60 * 1000;
    static final long FAILURE_PAUSE_MS = 5 * 60 * 1000;
    private static final Pattern TRACK_URL = Pattern.compile("open\\.spotify\\.com/(?:intl-[a-z]+/)?track/([A-Za-z0-9]+)");
    private static final Pattern DECORATION = Pattern.compile("\\s*[\\[(（].*?[\\])）]|\\s+-\\s+.*$");
    private static final Logger LOGGER = LoggerFactory.getLogger(SpotifyLyricsClient.class);

    private final SpotifySourceManager source;
    private final DiskCache cache;
    private final AtomicLong pausedUntil = new AtomicLong();

    public SpotifyLyricsClient(SpotifySourceManager source) {
        this(source, DiskCache.defaultCache());
    }

    SpotifyLyricsClient(SpotifySourceManager source, DiskCache cache) {
        this.source = source;
        this.cache = cache;
    }

    @Nullable
    public Lyrics find(AudioTrack track) {
        String path = "spotify-lyrics/" + TranslationJobs.sha256(track.getIdentifier()) + ".json";
        Stored stored = cache.read(path, Stored.class);
        if (stored != null && stored.version() == VERSION
                && (stored.lyrics() != null || System.currentTimeMillis() - stored.at() < MISS_RETRY_MS)) {
            return stored.lyrics();
        }
        if (paused(System.currentTimeMillis())) return null;
        CompletableFuture<Optional<Lyrics>> job = CompletableFuture.supplyAsync(() -> {
            try {
                return Optional.ofNullable(fetch(track));
            } catch (IOException e) {
                pause(e, System.currentTimeMillis());
                throw new UncheckedIOException(e);
            } catch (RuntimeException e) {
                pause(e, System.currentTimeMillis());
                throw e;
            }
        }, TranslationJobs.EXECUTOR);
        job.thenAccept(found -> cache.write(path, new Stored(found.orElse(null), System.currentTimeMillis(), VERSION)));
        try {
            return job.get(WAIT_MS, TimeUnit.MILLISECONDS).orElse(null);
        } catch (TimeoutException e) {
            LOGGER.info("spotify lyrics for {} are taking longer than {}ms, keeping them for the next time", track.getInfo().title, WAIT_MS);
            return null;
        } catch (ExecutionException e) {
            LOGGER.info("spotify lyrics lookup failed for {}: {}", track.getInfo().title, e.getCause() == null ? e : e.getCause().toString());
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    boolean paused(long now) {
        return now < pausedUntil.get();
    }

    void pause(Throwable error, long now) {
        long previous = pausedUntil.getAndSet(now + FAILURE_PAUSE_MS);
        if (previous <= now) {
            LOGGER.warn("spotify lyrics failed ({}), skipping spotify lyrics for {}s", error.toString(), FAILURE_PAUSE_MS / 1000);
        }
    }

    @Nullable
    private Lyrics fetch(AudioTrack track) throws IOException {
        AudioTrackInfo info = track.getInfo();
        String id = spotifyId(track);
        AudioTrackInfo matched = info;
        if (id == null) {
            AudioTrack found = search(info);
            if (found == null) return null;
            id = found.getIdentifier();
            matched = found.getInfo();
        }
        AudioLyrics lyrics = source.getLyrics(id);
        Lyrics converted = lyrics == null ? null : toLyrics(lyrics, matched.title, matched.author);
        if (converted == null) {
            LOGGER.info("spotify has no lyrics for {} (spotify track {})", info.title, id);
            return null;
        }
        LOGGER.info("found {} lyrics for {} on spotify ({}, track {})", converted.hasSynced() ? "synced" : "plain", info.title, lyrics.getProvider(), id);
        return converted;
    }

    @Nullable
    private AudioTrack search(AudioTrackInfo info) throws IOException {
        int seen = 0;
        for (String query : queries(info)) {
            AudioItem item = source.getSearch(query, false);
            List<AudioTrack> candidates = item instanceof AudioPlaylist playlist ? playlist.getTracks()
                    : item instanceof AudioTrack single ? List.of(single) : List.of();
            boolean byIsrc = query.startsWith("isrc:");
            for (int i = 0; i < candidates.size(); i++) {
                if (matches(info, candidates.get(i).getInfo(), byIsrc, i)) return candidates.get(i);
            }
            seen += candidates.size();
        }
        LOGGER.info("no spotify track matched {} by {} among {} search result(s)", info.title, info.author, seen);
        return null;
    }

    static List<String> queries(AudioTrackInfo info) {
        List<String> queries = new ArrayList<>();
        if (info.isrc != null && !info.isrc.isBlank()) queries.add("isrc:" + info.isrc);
        String title = LrcLibClient.cleanTitle(info.title);
        String artist = LrcLibClient.firstArtist(info.author);
        List<String> names = LrcLibClient.channelNames(artist);
        String name = names.isEmpty() ? artist : names.get(names.size() > 1 ? 1 : 0);
        List<String> titles = new ArrayList<>();
        for (String[] pair : LrcLibClient.candidatePairs(title, artist)) {
            if (pair[1].length() < title.length() && LrcLibClient.matchKey(pair[1]).length() >= 2 && !titles.contains(pair[1])) titles.add(pair[1]);
        }
        titles.sort(java.util.Comparator.comparingInt(String::length));
        for (String core : titles) {
            if (queries.size() >= MAX_QUERIES) break;
            String query = (core + " " + name).trim();
            if (!queries.contains(query)) queries.add(query);
        }
        String full = (title + " " + artist).trim();
        if (!queries.contains(full)) queries.add(full);
        return queries;
    }

    @Nullable
    static String spotifyId(AudioTrack track) {
        if (track instanceof SpotifyAudioTrack) return track.getIdentifier();
        String uri = track.getInfo().uri;
        if (uri == null) return null;
        Matcher matcher = TRACK_URL.matcher(uri);
        return matcher.find() ? matcher.group(1) : null;
    }

    static boolean matches(AudioTrackInfo ours, AudioTrackInfo candidate, boolean byIsrc, int rank) {
        long gap = ours.length > 0 && candidate.length > 0 ? Math.abs(ours.length - candidate.length) : 0;
        if (gap > DURATION_TOLERANCE_MS) return false;
        if (byIsrc) return true;
        String ourKey = LrcLibClient.matchKey(ours.title + " " + ours.author);
        String title = LrcLibClient.matchKey(DECORATION.matcher(candidate.title == null ? "" : candidate.title).replaceAll(""));
        if (!title.isEmpty() && ourKey.contains(title)) return true;
        String artist = LrcLibClient.matchKey(LrcLibClient.firstArtist(candidate.author));
        return rank < SAME_ARTIST_RANKS && ours.length > 0 && gap <= SAME_ARTIST_TOLERANCE_MS
                && artist.length() >= 3 && ourKey.contains(artist);
    }

    @Nullable
    static Lyrics toLyrics(AudioLyrics lyrics, String title, String artist) {
        List<AudioLyrics.Line> lines = lyrics.getLines() == null ? List.of() : lyrics.getLines();
        boolean timed = false;
        StringBuilder plain = new StringBuilder();
        for (AudioLyrics.Line line : lines) {
            Duration at = line.getTimestamp();
            if (at != null && !at.isZero()) timed = true;
            plain.append(line.getLine() == null ? "" : line.getLine()).append('\n');
        }
        if (lines.isEmpty() && lyrics.getText() != null) plain.append(lyrics.getText());
        String text = plain.toString().strip();
        if (text.isEmpty()) return null;
        List<LyricLine> synced = new ArrayList<>();
        if (timed) {
            for (AudioLyrics.Line line : lines) {
                Duration at = line.getTimestamp();
                synced.add(new LyricLine(at == null ? 0 : at.toMillis(), line.getLine() == null ? "" : line.getLine().strip()));
            }
        }
        return new Lyrics(title, artist, text, synced, false);
    }
}
