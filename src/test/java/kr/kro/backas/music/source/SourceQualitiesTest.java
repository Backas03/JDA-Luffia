package kr.kro.backas.music.source;

import com.sedmelluq.discord.lavaplayer.tools.JsonBrowser;
import com.sedmelluq.discord.lavaplayer.track.AudioReference;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import com.sedmelluq.discord.lavaplayer.track.AudioTrackInfo;
import com.sedmelluq.discord.lavaplayer.track.BaseAudioTrack;
import com.sedmelluq.discord.lavaplayer.track.playback.LocalAudioTrackExecutor;
import dev.lavalink.youtube.track.format.StreamFormat;
import dev.lavalink.youtube.track.format.TrackFormats;
import org.apache.http.entity.ContentType;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

class SourceQualitiesTest {

    private static StreamFormat format(String mime, int itag, long bitrate, long contentLength) {
        return new StreamFormat(ContentType.parse(mime), itag, bitrate, contentLength, 2,
                "https://example.invalid/videoplayback?itag=" + itag, null, null, null, true, false);
    }

    private static StreamFormat opus251() {
        return format("audio/webm; codecs=\"opus\"", 251, 141_000, 3_543_210);
    }

    private static StreamFormat aac140() {
        return format("audio/mp4; codecs=\"mp4a.40.2\"", 140, 130_000, 3_560_000);
    }

    private static JsonBrowser json(String text) throws IOException {
        return JsonBrowser.parse(text);
    }

    private static final class FakeTrack extends BaseAudioTrack {
        FakeTrack(String identifier) {
            super(new AudioTrackInfo("t", "a", 1000, identifier, false, "https://example.invalid/" + identifier));
        }

        @Override
        public void process(LocalAudioTrackExecutor executor) {
        }
    }

    // youtube-source 가 플레이어 응답의 포맷마다 extractFormat 을 부르는 흐름을 흉내 낸다.
    private static TrackFormats extract(List<StreamFormat> formats, List<JsonBrowser> jsons, boolean live) {
        List<StreamFormat> out = new ArrayList<>();
        for (int i = 0; i < formats.size(); i++) {
            int before = out.size();
            out.add(formats.get(i));
            SourceQualities.noteFormats(jsons.get(i), out, before, live);
        }
        return new TrackFormats(out, "");
    }

    @Test
    void recordsTheFormatYoutubeSourceWillPlayWithItsRealNumbers() throws Throwable {
        SourceQualities qualities = new SourceQualities();
        List<String> notified = new ArrayList<>();
        qualities.onRecorded(notified::add);

        qualities.capture("abc123", () -> extract(List.of(aac140(), opus251()),
                List.of(json("{\"averageBitrate\":129880,\"audioSampleRate\":\"44100\",\"approxDurationMs\":\"220481\"}"),
                        json("{\"averageBitrate\":128620,\"audioSampleRate\":\"48000\",\"approxDurationMs\":\"220481\"}")), false));

        assertEquals("Opus 129kbps · 48kHz", qualities.of(new FakeTrack("abc123")).label());
        assertEquals(List.of("abc123"), notified);
    }

    @Test
    void sameQualityAgainDoesNotRedrawTheCard() throws Throwable {
        SourceQualities qualities = new SourceQualities();
        List<String> notified = new ArrayList<>();
        qualities.onRecorded(notified::add);
        for (int i = 0; i < 2; i++) {
            qualities.capture("abc123", () -> extract(List.of(opus251()),
                    List.of(json("{\"averageBitrate\":128620,\"audioSampleRate\":\"48000\"}")), false));
        }
        assertEquals(1, notified.size());
    }

    @Test
    void spotifyTracksFollowTheirYoutubeMirror() throws Throwable {
        SourceQualities qualities = new SourceQualities();
        qualities.capture("yt-video", () -> extract(List.of(opus251()),
                List.of(json("{\"averageBitrate\":151000,\"audioSampleRate\":\"48000\"}")), false));
        AudioTrack youtube = new FakeTrack("yt-video");

        assertSame(youtube, qualities.recordingMirrors(track -> youtube).apply(spotifyTrack()));
        assertEquals("Opus 151kbps · 48kHz", qualities.of(new FakeTrack("spotify-id")).label());
    }

    @Test
    void unresolvedMirrorLeavesNothing() {
        SourceQualities qualities = new SourceQualities();
        qualities.recordingMirrors(track -> AudioReference.NO_TRACK).apply(spotifyTrack());
        assertEquals("spotify-id", qualities.videoIdOf(new FakeTrack("spotify-id")));
        assertNull(qualities.of(new FakeTrack("spotify-id")));
    }

    @Test
    void noPlayableFormatIsIgnoredInsteadOfBreakingPlayback() throws Throwable {
        SourceQualities qualities = new SourceQualities();
        TrackFormats empty = qualities.capture("abc123", () -> new TrackFormats(List.of(), ""));
        assertEquals(0, empty.getFormats().size());
        assertNull(qualities.of(new FakeTrack("abc123")));
    }

    @Test
    void unknownTrackHasNoQuality() {
        assertNull(new SourceQualities().of(new FakeTrack("never-played")));
        assertNull(new SourceQualities().of(null));
    }

    private static com.github.topi314.lavasrc.mirror.MirroringAudioTrack spotifyTrack() {
        return new com.github.topi314.lavasrc.mirror.MirroringAudioTrack(
                new AudioTrackInfo("t", "a", 1000, "spotify-id", false, "https://open.spotify.com/track/spotify-id"),
                null, null, null, null, null, false, null) {
            @Override
            protected com.sedmelluq.discord.lavaplayer.track.InternalAudioTrack createAudioTrack(
                    AudioTrackInfo trackInfo, com.sedmelluq.discord.lavaplayer.tools.io.SeekableInputStream stream) {
                return null;
            }

            @Override
            protected AudioTrack makeShallowClone() {
                return this;
            }
        };
    }
}
