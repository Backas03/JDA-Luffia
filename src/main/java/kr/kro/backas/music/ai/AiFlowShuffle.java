package kr.kro.backas.music.ai;

import com.sedmelluq.discord.lavaplayer.track.AudioTrack;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;

public final class AiFlowShuffle {

    private static final int MAX_SUMMARY_SEGMENTS = 6;
    private static final Map<String, String> GENRE_NAMES = Map.ofEntries(
            Map.entry("vocaloid", "보카로"),
            Map.entry("anime", "애니송"),
            Map.entry("j-pop", "J-pop"),
            Map.entry("j-rock", "J-rock"),
            Map.entry("k-pop", "K-pop"),
            Map.entry("k-ballad", "K-발라드"),
            Map.entry("hip-hop", "힙합"),
            Map.entry("r&b", "R&B"),
            Map.entry("pop", "팝"),
            Map.entry("rock", "록"),
            Map.entry("ballad", "발라드"),
            Map.entry("edm", "EDM"),
            Map.entry("jazz", "재즈"),
            Map.entry("classical", "클래식"),
            Map.entry("ost", "OST"),
            Map.entry("game", "게임 음악"),
            Map.entry("other", "기타"));
    private static final Map<String, String> LANGUAGE_NAMES = Map.of(
            "ko", "한국어",
            "ja", "일본어",
            "en", "영어",
            "zh", "중국어",
            "instrumental", "연주곡",
            "other", "기타");

    private AiFlowShuffle() {
    }

    public static List<AudioTrack> arrange(List<AudioTrack> tracks, Map<AudioTrack, AiTrackTagger.Tag> tags, Random random) {
        Map<String, Double> groupRanks = new HashMap<>();
        Map<AudioTrack, double[]> ranks = new IdentityHashMap<>();
        for (AudioTrack track : tracks) {
            AiTrackTagger.Tag tag = tags.get(track);
            String language = "l:" + region(tag);
            String genre = language + "|g:" + tag.genre();
            String energy = genre + "|e:" + energyBand(tag.energy());
            ranks.put(track, new double[]{
                    groupRanks.computeIfAbsent(language, key -> random.nextDouble()),
                    groupRanks.computeIfAbsent(genre, key -> random.nextDouble()),
                    groupRanks.computeIfAbsent(energy, key -> random.nextDouble()),
                    random.nextDouble()
            });
        }
        List<AudioTrack> ordered = new ArrayList<>(tracks);
        ordered.sort(Comparator.<AudioTrack>comparingDouble(track -> ranks.get(track)[0])
                .thenComparingDouble(track -> ranks.get(track)[1])
                .thenComparingDouble(track -> ranks.get(track)[2])
                .thenComparingDouble(track -> ranks.get(track)[3]));
        spreadArtists(ordered, tags);
        return ordered;
    }

    public static String summarize(List<AudioTrack> ordered, Map<AudioTrack, AiTrackTagger.Tag> tags) {
        List<String> segments = new ArrayList<>();
        String currentKey = null;
        String currentLabel = null;
        int count = 0;
        for (AudioTrack track : ordered) {
            AiTrackTagger.Tag tag = tags.get(track);
            if (tag == null) continue;
            String key = region(tag) + "|" + tag.genre();
            if (!key.equals(currentKey)) {
                if (currentKey != null) segments.add(currentLabel + " " + count + "곡");
                currentKey = key;
                currentLabel = label(tag);
                count = 0;
            }
            count++;
        }
        if (currentKey != null) segments.add(currentLabel + " " + count + "곡");
        if (segments.size() <= MAX_SUMMARY_SEGMENTS) return String.join(" → ", segments);
        return String.join(" → ", segments.subList(0, MAX_SUMMARY_SEGMENTS))
                + " → ... (총 " + segments.size() + "묶음)";
    }

    private static String label(AiTrackTagger.Tag tag) {
        String genre = GENRE_NAMES.getOrDefault(tag.genre(), tag.genre());
        String language = LANGUAGE_NAMES.getOrDefault(region(tag), region(tag));
        return genre + "(" + language + ")";
    }

    private static String region(AiTrackTagger.Tag tag) {
        return switch (tag.genre()) {
            case "k-pop", "k-ballad" -> "ko";
            case "j-pop", "j-rock", "vocaloid", "anime" -> "ja";
            default -> tag.language();
        };
    }

    private static String energyBand(int energy) {
        if (energy <= 2) return "low";
        if (energy >= 4) return "high";
        return "mid";
    }

    private static void spreadArtists(List<AudioTrack> ordered, Map<AudioTrack, AiTrackTagger.Tag> tags) {
        for (int i = 1; i < ordered.size(); i++) {
            String previous = artist(ordered.get(i - 1));
            if (previous.isEmpty() || !previous.equals(artist(ordered.get(i)))) continue;
            AiTrackTagger.Tag group = tags.get(ordered.get(i));
            for (int j = i + 1; j < ordered.size(); j++) {
                AiTrackTagger.Tag candidate = tags.get(ordered.get(j));
                if (!sameGroup(group, candidate)) break;
                if (!previous.equals(artist(ordered.get(j)))) {
                    Collections.swap(ordered, i, j);
                    break;
                }
            }
        }
    }

    private static boolean sameGroup(AiTrackTagger.Tag a, AiTrackTagger.Tag b) {
        return a != null && b != null
                && Objects.equals(region(a), region(b))
                && Objects.equals(a.genre(), b.genre());
    }

    private static String artist(AudioTrack track) {
        String author = track.getInfo().author;
        if (author == null) return "";
        return author.toLowerCase().replaceAll("\\s*-\\s*topic$|vevo$|official", "").replaceAll("[^\\p{L}\\p{N}]+", "");
    }
}
