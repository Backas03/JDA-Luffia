package kr.kro.backas.music.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import com.sedmelluq.discord.lavaplayer.track.AudioTrackInfo;
import kr.kro.backas.music.cache.DiskCache;
import kr.kro.backas.music.lyrics.TranslationClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.IntConsumer;

import org.jetbrains.annotations.Nullable;

public class AiTrackTagger {

    private static final Logger LOGGER = LoggerFactory.getLogger(AiTrackTagger.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    public static final int MAX_TRACKS = 200;
    private static final int BATCH_SIZE = 20;
    private static final int CACHE_SIZE = 20_000;
    private static final String DISK_PATH = "ai-tags-v2.json";
    private static final String SCHEMA_NAME = "track_tags";
    public static final List<String> GENRES = List.of(
            "vocaloid", "anime", "j-pop", "j-rock", "k-pop", "k-ballad", "hip-hop", "r&b",
            "pop", "rock", "ballad", "edm", "jazz", "classical", "ost", "game", "other");
    public static final List<String> LANGUAGES = List.of("ko", "ja", "en", "zh", "instrumental", "other");
    private static final String SYSTEM_PROMPT = String.join("\n",
            "You tag songs in a music queue so that similar songs can be played next to each other.",
            "For every numbered track give:",
            "- g: the genre, one of: " + String.join(", ", GENRES) + ". Use vocaloid for songs sung by a voice synthesizer, anime for anime theme songs, game for game music.",
            "- l: the language of the vocals: ko, ja, en, zh, instrumental for songs without vocals, other otherwise."
                    + " Do not guess it from the letters of the title: k-pop and k-ballad are ko and j-pop, j-rock, vocaloid and anime are ja,"
                    + " unless you know the song is sung entirely in another language.",
            "- e: energy from 1 (calm, slow) to 5 (intense, fast).",
            TrackHints.HINT_GUIDE,
            "Output JSON only: {\"r\": [{\"n\": 1, \"g\": \"j-pop\", \"l\": \"ja\", \"e\": 3}, ...]}",
            "with exactly one object per track, n = the track number.");

    public record Tag(String genre, String language, int energy) {
    }

    private final TranslationClient client;
    private final DiskCache diskCache;
    private volatile boolean dirty;
    private final Map<String, Tag> cache = Collections.synchronizedMap(
            new LinkedHashMap<>(64, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Tag> eldest) {
                    return size() > CACHE_SIZE;
                }
            });

    public AiTrackTagger(TranslationClient client) {
        this(client, DiskCache.defaultCache());
    }

    AiTrackTagger(TranslationClient client, DiskCache diskCache) {
        this.client = client;
        this.diskCache = diskCache;
        JsonNode stored = diskCache.read(DISK_PATH);
        if (stored != null) {
            stored.path("entries").fields().forEachRemaining(entry -> {
                JsonNode value = entry.getValue();
                String genre = value.path("g").asText("");
                String language = value.path("l").asText("");
                if (GENRES.contains(genre) && LANGUAGES.contains(language)) {
                    cache.put(entry.getKey(), new Tag(genre, language, Math.max(1, Math.min(5, value.path("e").asInt(3)))));
                }
            });
            LOGGER.info("loaded {} cached ai tag(s)", cache.size());
        }
    }

    @Nullable
    public Tag cached(AudioTrack track) {
        return cache.get(track.getIdentifier());
    }

    public void flush() {
        if (!dirty) return;
        dirty = false;
        ObjectNode stored = MAPPER.createObjectNode();
        ObjectNode entries = stored.putObject("entries");
        synchronized (cache) {
            cache.forEach((key, tag) -> entries.putObject(key)
                    .put("g", tag.genre())
                    .put("l", tag.language())
                    .put("e", tag.energy()));
        }
        diskCache.write(DISK_PATH, stored);
    }

    public Map<AudioTrack, Tag> tag(List<AudioTrack> tracks, IntConsumer onProgress) throws IOException {
        Map<AudioTrack, Tag> tags = new IdentityHashMap<>();
        List<AudioTrack> pending = new ArrayList<>();
        int done = 0;
        for (AudioTrack track : tracks) {
            Tag cached = cache.get(track.getIdentifier());
            if (cached == null) {
                pending.add(track);
                continue;
            }
            tags.put(track, cached);
            done++;
        }
        onProgress.accept(done);
        if (!pending.isEmpty()) {
            LOGGER.info("ai tagging {} track(s), {} cached", pending.size(), done);
            int cachedCount = done;
            List<Tag[]> results = BatchRunner.run(pending, BATCH_SIZE, client.parallelism(), this::requestBatch,
                    finished -> onProgress.accept(cachedCount + finished));
            for (int batchIndex = 0; batchIndex < results.size(); batchIndex++) {
                Tag[] result = results.get(batchIndex);
                int offset = batchIndex * BATCH_SIZE;
                for (int i = 0; i < result.length; i++) {
                    if (result[i] == null) continue;
                    AudioTrack track = pending.get(offset + i);
                    cache.put(track.getIdentifier(), result[i]);
                    tags.put(track, result[i]);
                }
            }
            dirty = true;
        }
        for (AudioTrack track : tracks) tags.putIfAbsent(track, fallback(track.getInfo()));
        return tags;
    }

    private Tag[] requestBatch(List<AudioTrack> batch) throws IOException {
        StringBuilder user = new StringBuilder("<tracks>\n");
        for (int i = 0; i < batch.size(); i++) {
            user.append(i + 1).append(". ").append(TrackHints.describe(batch.get(i).getInfo())).append('\n');
        }
        user.append("</tracks>\n");
        JsonNode result = client.requestJson(SYSTEM_PROMPT, user.toString(), SCHEMA_NAME, schema(batch.size()),
                32 * batch.size() + 32);
        Tag[] tags = new Tag[batch.size()];
        for (JsonNode entry : result.path("r")) {
            int n = entry.path("n").asInt(-1);
            String genre = entry.path("g").asText("");
            String language = entry.path("l").asText("");
            int energy = entry.path("e").asInt(3);
            if (n < 1 || n > batch.size() || tags[n - 1] != null) continue;
            if (!GENRES.contains(genre) || !LANGUAGES.contains(language)) continue;
            tags[n - 1] = new Tag(genre, language, Math.max(1, Math.min(5, energy)));
        }
        return tags;
    }

    private static Tag fallback(AudioTrackInfo info) {
        String script = TrackHints.script(info.title + " " + info.author);
        String language = script.contains("kana") ? "ja" : script.contains("hangul") ? "ko" : "other";
        return new Tag("other", language, 3);
    }

    private static ObjectNode schema(int size) {
        ObjectNode schema = MAPPER.createObjectNode();
        schema.put("type", "object");
        ObjectNode results = schema.putObject("properties").putObject("r");
        results.put("type", "array");
        results.put("minItems", size);
        results.put("maxItems", size);
        ObjectNode item = results.putObject("items");
        item.put("type", "object");
        ObjectNode itemProperties = item.putObject("properties");
        ObjectNode number = itemProperties.putObject("n");
        number.put("type", "integer");
        number.put("minimum", 1);
        number.put("maximum", size);
        ObjectNode genre = itemProperties.putObject("g");
        genre.put("type", "string");
        ArrayNode genres = genre.putArray("enum");
        GENRES.forEach(genres::add);
        ObjectNode language = itemProperties.putObject("l");
        language.put("type", "string");
        ArrayNode languages = language.putArray("enum");
        LANGUAGES.forEach(languages::add);
        ObjectNode energy = itemProperties.putObject("e");
        energy.put("type", "integer");
        energy.put("minimum", 1);
        energy.put("maximum", 5);
        item.putArray("required").add("n").add("g").add("l").add("e");
        schema.putArray("required").add("r");
        return schema;
    }
}
