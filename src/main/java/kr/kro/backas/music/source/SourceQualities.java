package kr.kro.backas.music.source;

import com.github.topi314.lavasrc.mirror.MirroringAudioTrackResolver;
import com.sedmelluq.discord.lavaplayer.tools.JsonBrowser;
import com.sedmelluq.discord.lavaplayer.track.AudioItem;
import com.sedmelluq.discord.lavaplayer.track.AudioPlaylist;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import dev.lavalink.youtube.CannotBeLoaded;
import dev.lavalink.youtube.track.format.StreamFormat;
import dev.lavalink.youtube.track.format.TrackFormats;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * 곡마다 실제로 재생한 유튜브 원본 스트림의 음질을 기억한다. youtube-source 가 재생 직전에 포맷을 고를 때
 * {@link QualityRecordingClients} 가 같은 선택({@link TrackFormats#getBestFormat()})을 엿보고 여기에 남긴다.
 * 스포티파이 곡은 미러링으로 연결된 유튜브 영상 id 를 따라가서 찾는다.
 */
public final class SourceQualities {
    private static final Logger LOGGER = LoggerFactory.getLogger(SourceQualities.class);
    private static final int MAX_ENTRIES = 512;
    private static final ThreadLocal<Map<StreamFormat, SourceQuality.Details>> CAPTURE = new ThreadLocal<>();

    private final Map<String, SourceQuality> byVideo = lruMap();
    private final Map<String, String> mirrors = lruMap();
    private volatile Consumer<String> listener = videoId -> {
    };

    private static <V> Map<String, V> lruMap() {
        return Collections.synchronizedMap(new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, V> eldest) {
                return size() > MAX_ENTRIES;
            }
        });
    }

    /** 새 음질이 기록되면 그 유튜브 영상 id 로 불린다. 재생 스레드에서 불리므로 오래 붙잡지 말 것. */
    public void onRecorded(Consumer<String> listener) {
        this.listener = listener;
    }

    @Nullable
    public SourceQuality of(@Nullable AudioTrack track) {
        String videoId = videoIdOf(track);
        return videoId == null ? null : byVideo.get(videoId);
    }

    @Nullable
    public String videoIdOf(@Nullable AudioTrack track) {
        if (track == null) return null;
        String id = track.getIdentifier();
        String mirrored = mirrors.get(id);
        return mirrored != null ? mirrored : id;
    }

    public MirroringAudioTrackResolver recordingMirrors(MirroringAudioTrackResolver resolver) {
        return track -> {
            AudioItem item = resolver.apply(track);
            AudioTrack resolved = playedTrack(item);
            if (resolved != null) mirrors.put(track.getIdentifier(), resolved.getIdentifier());
            return item;
        };
    }

    @Nullable
    private static AudioTrack playedTrack(@Nullable AudioItem item) {
        if (item instanceof AudioTrack track) return track;
        if (item instanceof AudioPlaylist playlist && !playlist.getTracks().isEmpty()) return playlist.getTracks().get(0);
        return null;
    }

    @FunctionalInterface
    interface FormatsLoader {
        TrackFormats load() throws CannotBeLoaded, IOException;
    }

    TrackFormats capture(String videoId, FormatsLoader loader) throws CannotBeLoaded, IOException {
        Map<StreamFormat, SourceQuality.Details> previous = CAPTURE.get();
        Map<StreamFormat, SourceQuality.Details> seen = new IdentityHashMap<>();
        CAPTURE.set(seen);
        TrackFormats formats;
        try {
            formats = loader.load();
        } finally {
            if (previous == null) CAPTURE.remove();
            else CAPTURE.set(previous);
        }
        record(videoId, formats, seen);
        return formats;
    }

    static void noteFormats(JsonBrowser json, List<StreamFormat> formats, int before, boolean live) {
        Map<StreamFormat, SourceQuality.Details> seen = CAPTURE.get();
        if (seen == null || formats.size() <= before) return;
        try {
            SourceQuality.Details details = SourceQuality.Details.from(json, live);
            for (int i = before; i < formats.size(); i++) seen.put(formats.get(i), details);
        } catch (RuntimeException e) {
            LOGGER.debug("failed to read stream format details", e);
        }
    }

    // 재생을 방해하지 않도록 여기서 생긴 예외는 삼킨다.
    private void record(String videoId, @Nullable TrackFormats formats, Map<StreamFormat, SourceQuality.Details> seen) {
        if (videoId == null || formats == null) return;
        try {
            StreamFormat best = formats.getBestFormat();
            SourceQuality quality = SourceQuality.of(best, seen.get(best));
            if (quality == null) return;
            SourceQuality previous = byVideo.put(videoId, quality);
            if (!quality.equals(previous)) listener.accept(videoId);
        } catch (RuntimeException e) {
            LOGGER.debug("failed to record source quality for {}", videoId, e);
        }
    }
}
