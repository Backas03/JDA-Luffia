package kr.kro.backas.music.service.youtube;

import com.sedmelluq.discord.lavaplayer.player.AudioPlayerManager;
import kr.kro.backas.music.service.MusicService;

import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;

public class YoutubeService {

    public static String getThumbnailURL(String url) {
        String videoId = extractVideoId(url);
        return "https://img.youtube.com/vi/" + videoId + "/0.jpg";
    }

    private static String extractVideoId(String url) {
        String videoId = null;
        String pattern = "(?<=watch\\?v=|/videos/|embed\\/|youtu.be\\/|\\/v\\/|\\/e\\/|watch\\?v%3D|watch\\?feature=player_embedded&v=|%2Fvideos%2F|embed%\u200C\u200B2F|youtu.be%2F|\\/v%2F)[^#\\&\\?\\n]*";
        java.util.regex.Pattern compiledPattern = java.util.regex.Pattern.compile(pattern);
        java.util.regex.Matcher matcher = compiledPattern.matcher(url);
        if (matcher.find()) {
            videoId = matcher.group();
        }
        return videoId;
    }
}
