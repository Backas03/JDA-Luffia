package kr.kro.backas.music.lyrics;

import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import com.sedmelluq.discord.lavaplayer.track.AudioTrackInfo;
import com.sedmelluq.discord.lavaplayer.source.http.HttpAudioSourceManager;
import com.sedmelluq.discord.lavaplayer.container.MediaContainerDescriptor;
import com.sedmelluq.discord.lavaplayer.container.mp3.Mp3ContainerProbe;
import com.sedmelluq.discord.lavaplayer.source.http.HttpAudioTrack;
import kr.kro.backas.music.cache.DiskCache;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LyricsFeedbackTest {

    @TempDir
    Path dir;

    private static AudioTrack track(String identifier) {
        AudioTrackInfo info = new AudioTrackInfo("ray", "Kaguya", 301_000, identifier, false, "https://example.com/" + identifier);
        return new HttpAudioTrack(info, new MediaContainerDescriptor(new Mp3ContainerProbe(), null), new HttpAudioSourceManager());
    }

    @Test
    void asksOnlyAboutAiTranscribedLyrics() {
        assertTrue(LyricsFeedback.asksAbout(Lyrics.Source.AI.label()));
        assertFalse(LyricsFeedback.asksAbout(Lyrics.Source.LRCLIB.label()));
        assertFalse(LyricsFeedback.asksAbout(null));
    }

    @Test
    void keepsOneVotePerUserWithTheirId() {
        LyricsFeedback feedback = new LyricsFeedback(new DiskCache(dir));
        AudioTrack track = track("abc123");
        feedback.open(track, "42", "qwen", 1_000);
        String key = LyricsFeedback.keyOf(track);

        feedback.record(key, "111", "alice", true, 2_000);
        feedback.record(key, "222", "bob", false, 3_000);
        LyricsFeedback.Report report = feedback.record(key, "111", "alice", false, 4_000);

        assertEquals(2, report.votes().size());
        assertEquals("bad", report.votes().get("111").vote());
        assertEquals("alice", report.votes().get("111").userName());
        assertEquals("bad", report.votes().get("222").vote());
        assertEquals("ray", report.title());
        assertEquals("qwen", report.translationModel());
        assertEquals("42", report.guildId());
    }

    @Test
    void reopeningAFinishedSongKeepsEarlierVotes() {
        LyricsFeedback feedback = new LyricsFeedback(new DiskCache(dir));
        AudioTrack track = track("abc123");
        feedback.open(track, "42", "qwen", 1_000);
        feedback.record(LyricsFeedback.keyOf(track), "111", "alice", true, 2_000);
        feedback.open(track, "42", "qwen", 5_000);
        LyricsFeedback.Report report = feedback.record(LyricsFeedback.keyOf(track), "222", "bob", true, 6_000);
        assertEquals(2, report.votes().size());
        assertEquals(5_000, report.finishedAt());
    }

    @Test
    void votesForAnUnknownSongAreIgnored() {
        LyricsFeedback feedback = new LyricsFeedback(new DiskCache(dir));
        assertNull(feedback.record("missing", "111", "alice", true, 1_000));
    }

    @Test
    void buttonIdsFitDiscordLimits() {
        String id = LyricsFeedback.PREFIX + LyricsFeedback.GOOD + ":" + LyricsFeedback.keyOf(track("x".repeat(500)));
        assertTrue(id.length() <= 100, id);
    }
}
