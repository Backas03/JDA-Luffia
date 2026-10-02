package kr.kro.backas.music.ai;

import com.sedmelluq.discord.lavaplayer.player.AudioPlayerManager;
import com.sedmelluq.discord.lavaplayer.source.AudioSourceManager;
import com.sedmelluq.discord.lavaplayer.track.AudioItem;
import com.sedmelluq.discord.lavaplayer.track.AudioPlaylist;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import com.sedmelluq.discord.lavaplayer.track.AudioTrackInfo;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class YoutubeLookup {

    private static final Logger LOGGER = LoggerFactory.getLogger(YoutubeLookup.class);
    private static final long MIN_LENGTH_MS = 60_000;
    private static final long MAX_LENGTH_MS = 600_000;
    private static final List<String> SEARCH_PREFIXES = List.of("ytmsearch:", "ytsearch:");
    private static final Pattern VARIANT = Pattern.compile(
            "(?i)歌ってみた|弾いてみた|踊ってみた|cover|カバー|커버|mmd|耐久|medley|メドレー|메들리|reaction|nightcore|sped ?up|slowed"
                    + "|karaoke|カラオケ|노래방|off ?vocal|instrumental|1 ?hour|1時間|【live】|[(\\[]live[)\\]]|live clip|live ver"
                    + "|concert|ライブ|라이브");
    private static final Pattern QUOTED_TITLE = Pattern.compile("[「『]([^」』]+)[」』]");
    private static final Pattern ARTIST_SEPARATOR = Pattern.compile("\\s*(?:/|,|&|、|×|\\bx\\b|\\band\\b)\\s*");
    private static final Pattern VARIANT_REQUEST = Pattern.compile(
            "(?i)커버|cover|歌ってみた|라이브|live|ライブ|콘서트|리믹스|remix|노래방|karaoke|메들리|medley|inst|반주|연주");

    private YoutubeLookup() {
    }

    @Nullable
    public static AudioTrack searchFirst(AudioPlayerManager manager, String query) {
        for (String prefix : SEARCH_PREFIXES) {
            try {
                AudioItem item = manager.loadItemSync(prefix + query);
                if (item instanceof AudioPlaylist playlist && !playlist.getTracks().isEmpty()) {
                    return playlist.getTracks().get(0);
                }
                if (item instanceof AudioTrack track) return track;
            } catch (RuntimeException e) {
                LOGGER.warn("youtube search failed for '{}{}': {}", prefix, query, e.toString());
            }
        }
        return null;
    }

    @Nullable
    public static String videoIdOf(AudioPlayerManager manager, AudioTrack track) {
        if (isYoutube(track)) return track.getIdentifier();
        AudioTrackInfo info = track.getInfo();
        AudioTrack found = searchFirst(manager, info.author + " " + info.title);
        return found == null ? null : found.getIdentifier();
    }

    public static boolean isVariant(AudioTrackInfo info) {
        return info.title != null && VARIANT.matcher(info.title).find();
    }

    public static boolean wantsVariants(String request) {
        return VARIANT_REQUEST.matcher(request).find();
    }

    public static void collectMix(AudioPlayerManager manager, String videoId, Set<String> seen, List<AudioTrack> into, int limit,
                                  boolean allowVariants) {
        AudioItem item;
        try {
            item = manager.loadItemSync("https://www.youtube.com/watch?v=" + videoId + "&list=RD" + videoId);
        } catch (RuntimeException e) {
            LOGGER.warn("failed to load youtube mix for {}: {}", videoId, e.toString());
            return;
        }
        if (!(item instanceof AudioPlaylist playlist)) return;
        for (AudioTrack track : playlist.getTracks()) {
            if (into.size() >= limit) return;
            if (videoId.equals(track.getIdentifier())) continue;
            if (!isPlayableLength(track.getInfo())) continue;
            if (!allowVariants && isVariant(track.getInfo())) continue;
            if (!markSeen(seen, track.getInfo())) continue;
            into.add(track);
        }
    }

    public static boolean isPlayableLength(AudioTrackInfo info) {
        return !info.isStream && info.length >= MIN_LENGTH_MS && info.length <= MAX_LENGTH_MS;
    }

    public static boolean markSeen(Set<String> seen, AudioTrackInfo info) {
        List<String> keys = keys(info);
        if (keys.stream().anyMatch(seen::contains)) return false;
        seen.addAll(keys);
        return true;
    }

    public static boolean isYoutube(AudioTrack track) {
        AudioSourceManager source = track.getSourceManager();
        return source != null && "youtube".equals(source.getSourceName());
    }

    public static List<String> keys(AudioTrackInfo info) {
        List<String> keys = new ArrayList<>(6);
        if (info.identifier != null) keys.add("id:" + info.identifier);
        if (info.title == null) return keys;
        addTitleKey(keys, info.title);
        int dash = info.title.lastIndexOf(" - ");
        if (dash >= 0) addTitleKey(keys, info.title.substring(dash + 3));
        Matcher quoted = QUOTED_TITLE.matcher(info.title);
        while (quoted.find()) addTitleKey(keys, quoted.group(1));
        if (info.author != null && !info.author.isBlank()) {
            String withoutArtist = info.title;
            for (String part : ARTIST_SEPARATOR.split(info.author)) {
                String name = part.strip();
                if (name.length() < 2) continue;
                withoutArtist = Pattern.compile(Pattern.quote(name), Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE)
                        .matcher(withoutArtist).replaceAll(" ");
            }
            addTitleKey(keys, withoutArtist);
        }
        return keys;
    }

    private static void addTitleKey(List<String> keys, String title) {
        String normalized = normalizeTitle(title);
        if (normalized.codePointCount(0, normalized.length()) < 2) return;
        String key = "t:" + normalized;
        if (!keys.contains(key)) keys.add(key);
    }

    static String normalizeTitle(@Nullable String title) {
        if (title == null) return "";
        return title.toLowerCase()
                .replaceAll("\\([^)]*\\)|\\[[^\\]]*\\]|【[^】]*】|（[^）]*）|［[^］]*］", " ")
                .replaceAll("\\b(feat|ft)(\\.|\\s).*$", " ")
                .replaceAll("(?i)official|music video|lyric video|lyrics|\\bmv\\b|\\bm/v\\b", " ")
                .replaceAll("[^\\p{L}\\p{N}]+", "");
    }
}
