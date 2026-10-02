package kr.kro.backas.music.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import kr.kro.backas.music.lyrics.SongResolver;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class AiSongResolverTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void readsTitleAndArtist() throws Exception {
        SongResolver.Song song = AiSongResolver.parse(MAPPER.readTree("{\"title\": \" 夜明けの歌 \", \"artist\": \"Sample  Band\"}"));
        assertNotNull(song);
        assertEquals("夜明けの歌", song.title());
        assertEquals("Sample Band", song.artist());
    }

    @Test
    void allowsAnUnknownArtist() throws Exception {
        SongResolver.Song song = AiSongResolver.parse(MAPPER.readTree("{\"title\": \"夜明けの歌\", \"artist\": \"\"}"));
        assertNotNull(song);
        assertEquals("", song.artist());
    }

    @Test
    void rejectsNonSongsAndLinks() throws Exception {
        assertNull(AiSongResolver.parse(MAPPER.readTree("{\"title\": \"\", \"artist\": \"Sample Band\"}")));
        assertNull(AiSongResolver.parse(MAPPER.readTree("{\"title\": \"https://evil.example\", \"artist\": \"x\"}")));
        assertNull(AiSongResolver.parse(MAPPER.readTree("{}")));
    }
}
