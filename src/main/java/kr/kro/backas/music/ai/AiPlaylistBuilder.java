package kr.kro.backas.music.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sedmelluq.discord.lavaplayer.player.AudioPlayerManager;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import kr.kro.backas.music.lyrics.TranslationClient;
import kr.kro.backas.music.lyrics.TranslationJobs;
import kr.kro.backas.util.DiscordSafe;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

public class AiPlaylistBuilder {

    private static final Logger LOGGER = LoggerFactory.getLogger(AiPlaylistBuilder.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    public static final int MAX_COUNT = 50;
    private static final int MAX_SUGGESTIONS = 10;
    private static final int MIX_BATCH = 25;
    private static final int MIX_SURPLUS = 5;
    private static final int MAX_POOL = 120;
    private static final String SCHEMA_NAME = "song_suggestions";
    private static final String SYSTEM_PROMPT = String.join("\n",
            "You suggest songs for a Korean music bot.",
            "First write topic: the listener's request restated in English.",
            TrackHints.SLANG_GUIDE,
            PromptSafe.DATA_RULE,
            "Then list well-known songs that clearly match the topic.",
            "Only list real songs you are sure exist, written the way they are usually written (original script is fine, e.g. artist ハチ, title 砂の惑星).",
            "Prefer different artists. Never invent songs.",
            "Output JSON only: {\"topic\": \"...\", \"songs\": [{\"artist\": \"...\", \"title\": \"...\"}]}");

    private record Suggestion(String artist, String title) {
    }

    private final TranslationClient client;
    private final AiShuffleClassifier classifier;

    public AiPlaylistBuilder(TranslationClient client, AiShuffleClassifier classifier) {
        this.client = client;
        this.classifier = classifier;
    }

    public List<AudioTrack> build(AudioPlayerManager manager, String request, String criteria, int count, int perArtist,
                                  Consumer<String> onStatus) throws IOException {
        int target = Math.max(1, Math.min(MAX_COUNT, count));
        String topic = criteria.isBlank() ? request : request + " (조건: " + criteria + ")";
        boolean allowVariants = YoutubeLookup.wantsVariants(request + " " + criteria);

        onStatus.accept("AI 가 대표곡을 고르고 있습니다...");
        List<Suggestion> suggestions = suggest(topic, Math.min(target, MAX_SUGGESTIONS));
        if (suggestions.isEmpty()) return List.of();

        onStatus.accept("유튜브에서 곡을 찾고 있습니다...");
        List<CompletableFuture<AudioTrack>> searches = new ArrayList<>();
        for (Suggestion suggestion : suggestions) {
            searches.add(CompletableFuture.supplyAsync(
                    () -> YoutubeLookup.searchFirst(manager, suggestion.artist() + " " + suggestion.title()),
                    TranslationJobs.EXECUTOR));
        }
        Set<String> seen = new HashSet<>();
        List<AudioTrack> seeds = new ArrayList<>();
        for (CompletableFuture<AudioTrack> search : searches) {
            AudioTrack track = search.join();
            if (track == null || !YoutubeLookup.isPlayableLength(track.getInfo())) continue;
            if (!allowVariants && YoutubeLookup.isVariant(track.getInfo())) continue;
            if (YoutubeLookup.markSeen(seen, track.getInfo())) seeds.add(track);
        }
        if (seeds.isEmpty()) return List.of();

        List<AudioTrack> matchedSeeds = filter(criteria, seeds, target, 0, onStatus);
        List<AudioTrack> matchedOthers = new ArrayList<>();
        int poolSize = seeds.size();
        List<AudioTrack> mixSeeds = new ArrayList<>(seeds);
        Collections.shuffle(mixSeeds);
        for (AudioTrack seed : mixSeeds) {
            int found = matchedSeeds.size() + matchedOthers.size();
            if (distinctArtists(matchedSeeds, matchedOthers, perArtist) >= target + MIX_SURPLUS || poolSize >= MAX_POOL) break;
            onStatus.accept("비슷한 곡을 모으고 있습니다 (" + found + " / " + target + "곡)");
            List<AudioTrack> fresh = new ArrayList<>();
            YoutubeLookup.collectMix(manager, seed.getIdentifier(), seen, fresh, Math.min(MIX_BATCH, MAX_POOL - poolSize), allowVariants);
            poolSize += fresh.size();
            matchedOthers.addAll(filter(criteria, fresh, target, found, onStatus));
        }

        Collections.shuffle(matchedOthers);
        List<AudioTrack> ranked = new ArrayList<>(matchedSeeds);
        ranked.addAll(matchedOthers);
        List<AudioTrack> picked = ArtistVariety.pick(ranked, track -> ArtistVariety.names(track.getInfo()), perArtist, target);
        Collections.shuffle(picked);
        picked = ArtistVariety.spread(picked, track -> ArtistVariety.names(track.getInfo()));
        LOGGER.info("ai playlist for '{}': {} suggestion(s), {} seed(s), pool {}, picked {}",
                topic, suggestions.size(), seeds.size(), poolSize, picked.size());
        return picked;
    }

    private static int distinctArtists(List<AudioTrack> seeds, List<AudioTrack> others, int perArtist) {
        List<AudioTrack> all = new ArrayList<>(seeds);
        all.addAll(others);
        return ArtistVariety.countWithinLimit(all, track -> ArtistVariety.names(track.getInfo()), perArtist);
    }

    private List<AudioTrack> filter(String criteria, List<AudioTrack> tracks, int target, int found,
                                    Consumer<String> onStatus) throws IOException {
        if (criteria.isBlank() || tracks.isEmpty()) return tracks;
        onStatus.accept("조건에 맞는 곡을 고르고 있습니다 (" + found + " / " + target + "곡)");
        Set<AudioTrack> fits = classifier.classify(criteria, tracks, done -> {
        });
        List<AudioTrack> matched = new ArrayList<>(tracks);
        matched.removeIf(track -> !fits.contains(track));
        return matched;
    }

    private List<Suggestion> suggest(String topic, int size) throws IOException {
        JsonNode result = client.requestJson(SYSTEM_PROMPT,
                "<request>" + PromptSafe.data(topic) + "</request>\nSuggest up to " + size + " songs.",
                SCHEMA_NAME, schema(size), 48 * size + 96);
        List<Suggestion> suggestions = new ArrayList<>();
        for (JsonNode song : result.path("songs")) {
            String artist = song.path("artist").asText("").strip();
            String title = song.path("title").asText("").strip();
            if (title.isEmpty() || DiscordSafe.hasLinkOrMention(artist + " " + title)) continue;
            suggestions.add(new Suggestion(artist, title));
        }
        LOGGER.info("ai suggested for '{}' ({}): {}", topic, result.path("topic").asText(""), suggestions);
        return suggestions;
    }

    private static ObjectNode schema(int size) {
        ObjectNode schema = MAPPER.createObjectNode();
        schema.put("type", "object");
        ObjectNode schemaProperties = schema.putObject("properties");
        ObjectNode topic = schemaProperties.putObject("topic");
        topic.put("type", "string");
        topic.put("maxLength", 120);
        ObjectNode songs = schemaProperties.putObject("songs");
        songs.put("type", "array");
        songs.put("minItems", 1);
        songs.put("maxItems", size);
        ObjectNode item = songs.putObject("items");
        item.put("type", "object");
        ObjectNode properties = item.putObject("properties");
        ObjectNode artist = properties.putObject("artist");
        artist.put("type", "string");
        artist.put("maxLength", 60);
        ObjectNode title = properties.putObject("title");
        title.put("type", "string");
        title.put("maxLength", 80);
        item.putArray("required").add("artist").add("title");
        schema.putArray("required").add("topic").add("songs");
        return schema;
    }
}
