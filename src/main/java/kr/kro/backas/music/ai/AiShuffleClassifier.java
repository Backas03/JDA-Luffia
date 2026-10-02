package kr.kro.backas.music.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
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
import java.util.Set;
import java.util.function.IntConsumer;

public class AiShuffleClassifier {

    private static final Logger LOGGER = LoggerFactory.getLogger(AiShuffleClassifier.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    public static final int MAX_TRACKS = 200;
    private static final int BATCH_SIZE = 20;
    private static final int CACHE_SIZE = 20_000;
    private static final String DISK_PATH = "ai-classify.json";
    private static final String SCHEMA_NAME = "queue_selection";
    private static final String SYSTEM_PROMPT = String.join("\n",
            "You pick songs from a music queue for a Korean user's request.",
            "The request is written in Korean and says which songs the user wants to hear, e.g. 일본 노래, 보카로 곡, 신나는 노래.",
            "For every numbered track decide whether it fits the request. Loose wording such as 위주로 still means: pick the tracks that fit.",
            TrackHints.HINT_GUIDE,
            "If the request does not narrow anything down, every track fits.",
            "Output JSON only: {\"r\": [{\"n\": 1, \"m\": true}, {\"n\": 2, \"m\": false}, ...]}",
            "with exactly one object per track, n = the track number, m = whether the track fits the request.");

    private final TranslationClient client;
    private final DiskCache diskCache;
    private volatile boolean dirty;
    private final Map<String, Boolean> cache = Collections.synchronizedMap(
            new LinkedHashMap<>(64, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
                    return size() > CACHE_SIZE;
                }
            });

    public AiShuffleClassifier(TranslationClient client) {
        this(client, DiskCache.defaultCache());
    }

    AiShuffleClassifier(TranslationClient client, DiskCache diskCache) {
        this.client = client;
        this.diskCache = diskCache;
        JsonNode stored = diskCache.read(DISK_PATH);
        if (stored != null) {
            stored.path("entries").fields().forEachRemaining(entry -> {
                if (entry.getValue().isBoolean()) cache.put(entry.getKey(), entry.getValue().asBoolean());
            });
            LOGGER.info("loaded {} cached ai classification(s)", cache.size());
        }
    }

    public void flush() {
        if (!dirty) return;
        dirty = false;
        ObjectNode stored = MAPPER.createObjectNode();
        ObjectNode entries = stored.putObject("entries");
        synchronized (cache) {
            cache.forEach(entries::put);
        }
        diskCache.write(DISK_PATH, stored);
    }

    public Set<AudioTrack> classify(String request, List<AudioTrack> tracks, IntConsumer onProgress) throws IOException {
        String normalized = request.strip().replaceAll("\\s+", " ");
        Set<AudioTrack> matched = Collections.newSetFromMap(new IdentityHashMap<>());
        List<AudioTrack> pending = new ArrayList<>();
        int done = 0;
        for (AudioTrack track : tracks) {
            Boolean cached = cache.get(cacheKey(normalized, track));
            if (cached == null) {
                pending.add(track);
                continue;
            }
            if (cached) matched.add(track);
            done++;
        }
        onProgress.accept(done);
        if (pending.isEmpty()) return matched;
        LOGGER.info("ai shuffle classifying {} track(s), {} cached, request={}", pending.size(), done, normalized);
        int cachedCount = done;
        List<Boolean[]> results = BatchRunner.run(pending, BATCH_SIZE, client.parallelism(),
                batch -> requestBatch(normalized, batch), finished -> onProgress.accept(cachedCount + finished));
        for (int batchIndex = 0; batchIndex < results.size(); batchIndex++) {
            Boolean[] fits = results.get(batchIndex);
            int offset = batchIndex * BATCH_SIZE;
            for (int i = 0; i < fits.length; i++) {
                AudioTrack track = pending.get(offset + i);
                if (fits[i] == null) continue;
                cache.put(cacheKey(normalized, track), fits[i]);
                if (fits[i]) matched.add(track);
            }
        }
        dirty = true;
        LOGGER.info("ai shuffle matched {} of {} track(s)", matched.size(), tracks.size());
        return matched;
    }

    private Boolean[] requestBatch(String request, List<AudioTrack> batch) throws IOException {
        StringBuilder user = new StringBuilder();
        user.append("<request>").append(PromptSafe.data(request)).append("</request>\n<tracks>\n");
        for (int i = 0; i < batch.size(); i++) {
            user.append(i + 1).append(". ").append(TrackHints.describe(batch.get(i).getInfo())).append('\n');
        }
        user.append("</tracks>\n");
        JsonNode result = client.requestJson(SYSTEM_PROMPT, user.toString(), SCHEMA_NAME, schema(batch.size()),
                20 * batch.size() + 32);
        Boolean[] fits = new Boolean[batch.size()];
        for (JsonNode entry : result.path("r")) {
            int n = entry.path("n").asInt(-1);
            JsonNode fit = entry.path("m");
            if (n < 1 || n > batch.size() || !fit.isBoolean() || fits[n - 1] != null) continue;
            fits[n - 1] = fit.asBoolean();
        }
        return fits;
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
        itemProperties.putObject("m").put("type", "boolean");
        item.putArray("required").add("n").add("m");
        schema.putArray("required").add("r");
        return schema;
    }

    private static String cacheKey(String request, AudioTrack track) {
        return request + "\n" + track.getIdentifier();
    }
}
