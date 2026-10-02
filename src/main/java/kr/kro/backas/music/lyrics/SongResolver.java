package kr.kro.backas.music.lyrics;

import com.sedmelluq.discord.lavaplayer.track.AudioTrackInfo;
import org.jetbrains.annotations.Nullable;

public interface SongResolver {

    record Song(String title, String artist) {
    }

    @Nullable
    Song resolve(AudioTrackInfo info);
}
