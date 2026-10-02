package kr.kro.backas.music.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sedmelluq.discord.lavaplayer.track.AudioTrackInfo;
import kr.kro.backas.music.lyrics.SongResolver;
import kr.kro.backas.music.lyrics.TranslationClient;
import kr.kro.backas.util.DiscordSafe;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

public class AiSongResolver implements SongResolver {

    private static final Logger LOGGER = LoggerFactory.getLogger(AiSongResolver.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int CACHE_SIZE = 500;
    private static final int MAX_NAME_LENGTH = 120;
    private static final String SCHEMA_NAME = "song_identity";
    private static final String SYSTEM_PROMPT = String.join("\n",
            "You identify the song in a video from the video title and the uploader, so its lyrics can be looked up.",
            "- title: the official song title only, written as in the video title, without quotes or brackets and without notes such as"
                    + " MV, Official Video, Lyric Video, オープニング映像, アニメ, 歌ってみた, cover, live, remaster, 가사, 자막 or feat. credits.",
            "- artist: the artist or band that performs the song, as a lyrics database would list it. For an anime or game song give the band or singer,"
                    + " not the anime, game or channel name. For a cover give the original artist."
                    + " Use the name written in the video title when it is there, otherwise the name you know. Leave it empty when you cannot tell.",
            "- The uploader is often a label, an anime, a game or a fan channel rather than the artist. Answer with the uploader only when it is clearly the artist's own channel."
                    + " A band named inside a tie-in note is the artist (「Song」（アニメ「Series It's BandName」オープニング映像） -> BandName).",
            "- If the video is not a single song (a talk, a stream, a playlist or a compilation), set title to an empty string.",
            "Output JSON only: {\"title\": \"...\", \"artist\": \"...\"}",
            PromptSafe.DATA_RULE);

    private final TranslationClient client;
    private final Map<String, Optional<Song>> cache = Collections.synchronizedMap(
            new LinkedHashMap<>(64, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Optional<Song>> eldest) {
                    return size() > CACHE_SIZE;
                }
            });

    public AiSongResolver(TranslationClient client) {
        this.client = client;
    }

    @Override
    @Nullable
    public Song resolve(AudioTrackInfo info) {
        if (!client.isEnabled() || !client.isLlm()) return null;
        String key = info.identifier == null || info.identifier.isBlank() ? info.title + "|" + info.author : info.identifier;
        Optional<Song> cached = cache.get(key);
        if (cached != null) return cached.orElse(null);
        Song song;
        try {
            String user = "<tracks>\ntitle: " + TrackHints.field(info.title) + "\nuploader: " + TrackHints.field(info.author) + "\n</tracks>\n";
            song = parse(client.requestJson(SYSTEM_PROMPT, user, SCHEMA_NAME, schema(), 96));
        } catch (IOException | RuntimeException e) {
            LOGGER.debug("ai song lookup failed for {}", info.title, e);
            return null;
        }
        cache.put(key, Optional.ofNullable(song));
        if (song != null) LOGGER.info("ai read '{}' as '{}' by '{}'", info.title, song.title(), song.artist());
        return song;
    }

    @Nullable
    static Song parse(JsonNode result) {
        String title = clean(result.path("title").asText(""));
        String artist = clean(result.path("artist").asText(""));
        if (title.isEmpty() || DiscordSafe.hasLinkOrMention(title + " " + artist)) return null;
        return new Song(title, artist);
    }

    private static String clean(String value) {
        String cleaned = value.replaceAll("\\s+", " ").strip();
        return cleaned.length() > MAX_NAME_LENGTH ? cleaned.substring(0, MAX_NAME_LENGTH) : cleaned;
    }

    private static ObjectNode schema() {
        ObjectNode schema = MAPPER.createObjectNode();
        schema.put("type", "object");
        ObjectNode properties = schema.putObject("properties");
        properties.putObject("title").put("type", "string");
        properties.putObject("artist").put("type", "string");
        schema.putArray("required").add("title").add("artist");
        return schema;
    }
}
