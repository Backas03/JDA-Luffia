package kr.kro.backas.music.service;
import com.sedmelluq.discord.lavaplayer.player.AudioPlayerManager;

public interface MusicService {

    String getIdentifier();

    void loadItem(String query, AudioPlayerManager manager);
}
