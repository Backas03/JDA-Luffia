package kr.kro.backas.music.service.youtube;

import org.jetbrains.annotations.Nullable;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class YoutubeService {
    private static final Pattern VIDEO_ID_PATTERN = Pattern.compile(
            "(?<=watch\\?v=|/videos/|embed\\/|youtu.be\\/|\\/v\\/|\\/e\\/|watch\\?v%3D|watch\\?feature=player_embedded&v=|%2Fvideos%2F|embed%‌​2F|youtu.be%2F|\\/v%2F)[^#\\&\\?\\n]*"
    );

    @Nullable
    public static String getThumbnailURL(@Nullable String url) {
        String videoId = extractVideoId(url);
        if (videoId == null || videoId.isBlank()) return null;
        return "https://img.youtube.com/vi/" + videoId + "/0.jpg";
    }

    @Nullable
    public static String extractVideoId(@Nullable String url) {
        if (url == null) return null;
        Matcher matcher = VIDEO_ID_PATTERN.matcher(url);
        if (matcher.find()) {
            return matcher.group();
        }
        return null;
    }
}
