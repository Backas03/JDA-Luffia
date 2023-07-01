package kr.kro.backas.music.service.youtube;

import com.sedmelluq.discord.lavaplayer.player.AudioPlayerManager;
import kr.kro.backas.music.service.MusicService;

public class YoutubeService implements MusicService {

    @Override
    public String getIdentifier() {
        return "ytsearch:";
    }

    @Override
    public void loadItem(String query, AudioPlayerManager manager) {

    }

    private String withIdentifier(String query) {
        return getIdentifier() + query;
    }
}
