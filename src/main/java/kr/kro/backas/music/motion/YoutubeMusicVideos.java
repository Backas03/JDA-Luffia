package kr.kro.backas.music.motion;

import com.sedmelluq.discord.lavaplayer.player.AudioPlayerManager;
import com.sedmelluq.discord.lavaplayer.track.AudioItem;
import com.sedmelluq.discord.lavaplayer.track.AudioPlaylist;
import com.sedmelluq.discord.lavaplayer.track.AudioReference;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import com.sedmelluq.discord.lavaplayer.track.AudioTrackInfo;
import dev.lavalink.youtube.YoutubeAudioSourceManager;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

final class YoutubeMusicVideos {

    record Candidate(String videoId, String title, String author, long lengthMs) {
    }

    static final long LENGTH_TOLERANCE_MS = 15_000;
    static final int MAX_CANDIDATES = 3;
    private static final Pattern DECORATION = Pattern.compile("\\s*[\\[(（].*?[\\])）]|\\s+-\\s+.*$");
    private static final Pattern NOT_A_LETTER = Pattern.compile("[^\\p{L}\\p{N}]");
    private static final List<String> OFFICIAL = List.of("mv", "musicvideo", "officialvideo", "ミュージックビデオ", "뮤직비디오");
    private static final List<String> OTHER_TAKES = List.of("cover", "live", "remix", "karaoke", "instrumental", "reaction", "nightcore",
            "spedup", "slowed", "offvocal", "歌ってみた", "カバー", "弾いてみた", "カラオケ", "ライブ", "커버", "라이브", "노래방");

    private YoutubeMusicVideos() {
    }

    static List<Candidate> search(AudioPlayerManager manager, YoutubeAudioSourceManager youtube, String title, @Nullable String artist,
                                  long lengthMs, String playedVideoId) {
        String query = (firstArtist(artist) + " " + cleanTitle(title) + " MV").trim();
        AudioItem found = youtube.loadItem(manager, new AudioReference(YoutubeAudioSourceManager.SEARCH_PREFIX + query, null));
        List<Candidate> results = new ArrayList<>();
        if (found instanceof AudioPlaylist playlist) {
            for (AudioTrack track : playlist.getTracks()) {
                AudioTrackInfo info = track.getInfo();
                if (!info.isStream) results.add(new Candidate(track.getIdentifier(), info.title, info.author, info.length));
            }
        }
        return pick(title, artist, lengthMs, playedVideoId, results);
    }

    static List<Candidate> pick(String title, @Nullable String artist, long lengthMs, String playedVideoId, List<Candidate> results) {
        String titleKey = key(cleanTitle(title));
        String artistKey = key(firstArtist(artist));
        List<Candidate> picked = new ArrayList<>();
        if (titleKey.length() < 2 || artistKey.length() < 2) return picked;
        for (Candidate candidate : results) {
            String candidateTitle = key(candidate.title());
            String candidateAuthor = candidate.author() == null ? "" : candidate.author();
            if (candidate.videoId().equals(playedVideoId) || candidateAuthor.endsWith("- Topic")) continue;
            if (lengthMs > 0 && candidate.lengthMs() > 0 && Math.abs(candidate.lengthMs() - lengthMs) > LENGTH_TOLERANCE_MS) continue;
            if (!candidateTitle.contains(titleKey)) continue;
            if (!candidateTitle.contains(artistKey) && !key(candidateAuthor).contains(artistKey)) continue;
            if (isOtherTake(candidateTitle, key(title))) continue;
            picked.add(candidate);
        }
        picked.sort(Comparator.comparing(candidate -> !isOfficial(key(candidate.title()))));
        return picked.size() > MAX_CANDIDATES ? new ArrayList<>(picked.subList(0, MAX_CANDIDATES)) : picked;
    }

    private static boolean isOfficial(String titleKey) {
        for (String word : OFFICIAL) {
            if (titleKey.contains(word)) return true;
        }
        return false;
    }

    private static boolean isOtherTake(String candidateTitle, String wantedTitle) {
        for (String word : OTHER_TAKES) {
            if (candidateTitle.contains(word) && !wantedTitle.contains(word)) return true;
        }
        return false;
    }

    static String cleanTitle(String title) {
        String cleaned = DECORATION.matcher(title == null ? "" : title).replaceAll("").strip();
        return cleaned.isEmpty() && title != null ? title.strip() : cleaned;
    }

    static String firstArtist(@Nullable String artist) {
        if (artist == null) return "";
        int comma = artist.indexOf(',');
        return (comma < 0 ? artist : artist.substring(0, comma)).strip();
    }

    static String key(@Nullable String text) {
        return text == null ? "" : NOT_A_LETTER.matcher(text.toLowerCase(Locale.ROOT)).replaceAll("");
    }
}
