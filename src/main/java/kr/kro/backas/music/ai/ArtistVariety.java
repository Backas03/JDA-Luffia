package kr.kro.backas.music.ai;

import com.sedmelluq.discord.lavaplayer.track.AudioTrackInfo;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class ArtistVariety {

    public static final int DEFAULT_PER_ARTIST = 2;
    public static final int DIVERSE_PER_ARTIST = 1;
    private static final Pattern SEPARATOR = Pattern.compile(
            "(?i)\\s*(?:/|,|&|、|×|\\bx\\b|\\band\\b|\\bfeat\\b\\.?|\\bft\\b\\.?|\\bwith\\b)\\s*");
    private static final Pattern TITLE_ARTIST = Pattern.compile("^(.+?)\\s*(?:[「『]|\\s['‘\"]|\\s[-–—]\\s)");
    private static final Pattern PARENTHETICAL = Pattern.compile("\\([^)]*\\)|（[^）]*）|\\[[^\\]]*\\]|【[^】]*】");
    private static final Pattern CHANNEL_NOISE = Pattern.compile(
            "(?i)\\s*-\\s*topic\\s*$|official|vevo|youtube|channel|公式|オフィシャル|チャンネル");

    private ArtistVariety() {
    }

    public static int perArtist(boolean diverse) {
        return diverse ? DIVERSE_PER_ARTIST : DEFAULT_PER_ARTIST;
    }

    public static Set<String> names(@Nullable String artists) {
        Set<String> names = new LinkedHashSet<>();
        if (artists == null) return names;
        for (String part : SEPARATOR.split(artists)) {
            String name = normalize(part);
            if (!name.isEmpty()) names.add(name);
        }
        return names;
    }

    public static Set<String> names(AudioTrackInfo info) {
        if (info.title != null) {
            Matcher matcher = TITLE_ARTIST.matcher(info.title.strip());
            if (matcher.find()) {
                Set<String> fromTitle = names(matcher.group(1));
                if (!fromTitle.isEmpty()) return fromTitle;
            }
        }
        return names(info.author);
    }

    static String normalize(String name) {
        String cleaned = PARENTHETICAL.matcher(name).replaceAll(" ");
        cleaned = CHANNEL_NOISE.matcher(cleaned).replaceAll(" ");
        return cleaned.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]+", "");
    }

    public static boolean overlaps(Set<String> artists, Collection<String> blocked) {
        return !Collections.disjoint(artists, blocked);
    }

    public static <T> List<T> pick(List<T> ranked, Function<T, Set<String>> artistsOf, int maxPerArtist, int limit) {
        List<T> skipped = new ArrayList<>();
        List<T> picked = withinLimit(ranked, artistsOf, maxPerArtist, limit, skipped);
        for (T item : skipped) {
            if (picked.size() >= limit) break;
            picked.add(item);
        }
        return spread(picked, artistsOf);
    }

    public static <T> List<T> limit(List<T> ranked, Function<T, Set<String>> artistsOf, int maxPerArtist, int limit) {
        return withinLimit(ranked, artistsOf, maxPerArtist, limit, new ArrayList<>());
    }

    public static <T> int countWithinLimit(List<T> items, Function<T, Set<String>> artistsOf, int maxPerArtist) {
        return limit(items, artistsOf, maxPerArtist, Integer.MAX_VALUE).size();
    }

    private static <T> List<T> withinLimit(List<T> ranked, Function<T, Set<String>> artistsOf, int maxPerArtist, int limit,
                                           List<T> skipped) {
        Map<String, Integer> counts = new HashMap<>();
        List<T> picked = new ArrayList<>();
        for (T item : ranked) {
            if (picked.size() >= limit) break;
            Set<String> artists = artistsOf.apply(item);
            boolean full = false;
            for (String artist : artists) {
                if (counts.getOrDefault(artist, 0) >= maxPerArtist) full = true;
            }
            if (full) {
                skipped.add(item);
                continue;
            }
            for (String artist : artists) counts.merge(artist, 1, Integer::sum);
            picked.add(item);
        }
        return picked;
    }

    public static <T> List<T> spread(List<T> items, Function<T, Set<String>> artistsOf) {
        List<T> remaining = new ArrayList<>(items);
        List<T> ordered = new ArrayList<>(items.size());
        Set<String> previous = Set.of();
        while (!remaining.isEmpty()) {
            int index = 0;
            for (int i = 0; i < remaining.size(); i++) {
                if (!overlaps(artistsOf.apply(remaining.get(i)), previous)) {
                    index = i;
                    break;
                }
            }
            T next = remaining.remove(index);
            ordered.add(next);
            previous = artistsOf.apply(next);
        }
        return ordered;
    }
}
