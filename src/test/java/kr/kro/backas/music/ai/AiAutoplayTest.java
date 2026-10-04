package kr.kro.backas.music.ai;

import com.sedmelluq.discord.lavaplayer.track.AudioTrackInfo;
import kr.kro.backas.music.ai.AiAutoplay.Role;
import kr.kro.backas.music.ai.AiAutoplay.SessionTrack;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AiAutoplayTest {

    private static SessionTrack track(String id, boolean auto, Role role) {
        return new SessionTrack(new AudioTrackInfo(id, "artist " + id, 200_000, id, false, "https://youtu.be/" + id), true, auto, role);
    }

    private static List<String> ids(List<SessionTrack> tracks) {
        return tracks.stream().map(track -> track.info().identifier).toList();
    }

    @Test
    void playingSongLeadsAndListenerQueueComesBeforeHistory() {
        List<SessionTrack> session = List.of(
                track("old1", false, Role.PLAYED), track("old2", false, Role.PLAYED),
                track("now", false, Role.PLAYING), track("next", false, Role.QUEUED));
        assertEquals(List.of("now", "next"), ids(AiAutoplay.seeds(session, 2, new Random(1))));
        assertEquals(List.of("now", "next", "old2"), ids(AiAutoplay.seeds(session, 3, new Random(1))));
    }

    @Test
    void automaticPicksSeedOnlyAfterEveryListenerChoice() {
        List<SessionTrack> session = List.of(
                track("autoOld", true, Role.PLAYED), track("autoNow", true, Role.PLAYING), track("next", false, Role.QUEUED));
        assertEquals(List.of("next", "autoNow"), ids(AiAutoplay.seeds(session, 2, new Random(1))));
        List<SessionTrack> onlyAuto = List.of(track("autoOld", true, Role.PLAYED), track("autoNow", true, Role.PLAYING));
        assertEquals(List.of("autoNow", "autoOld"), ids(AiAutoplay.seeds(onlyAuto, 2, new Random(1))));
    }

    @Test
    void seedsAreNotRepeated() {
        List<SessionTrack> session = List.of(track("same", false, Role.PLAYED), track("same", false, Role.PLAYING));
        assertEquals(List.of("same"), ids(AiAutoplay.seeds(session, 2, new Random(1))));
    }

    @Test
    void labelsTellTheModelWhoChoseEachSong() {
        assertEquals("playing", track("a", false, Role.PLAYING).label());
        assertEquals("queued", track("a", false, Role.QUEUED).label());
        assertEquals("played", track("a", false, Role.PLAYED).label());
        assertEquals("playing, auto", track("a", true, Role.PLAYING).label());
        assertEquals("queued, auto", track("a", true, Role.QUEUED).label());
        assertEquals("auto", track("a", true, Role.PLAYED).label());
    }
}
