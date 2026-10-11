package kr.kro.backas.music.lyrics;

import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;

public final class LyricsSources {

    private final LrcLibClient lrcLib;
    @Nullable
    private final SpotifyLyricsClient spotify;

    public LyricsSources(LrcLibClient lrcLib, @Nullable SpotifyLyricsClient spotify) {
        this.lrcLib = lrcLib;
        this.spotify = spotify;
    }

    public boolean hasSpotify() {
        return spotify != null;
    }

    @Nullable
    public Lyrics find(AudioTrack track) throws IOException {
        Lyrics fromLrcLib = null;
        IOException failure = null;
        try {
            fromLrcLib = lrcLib.find(track.getInfo());
        } catch (IOException e) {
            failure = e;
        }
        if (spotify == null || (fromLrcLib != null && (fromLrcLib.hasSynced() || fromLrcLib.instrumental()))) {
            if (fromLrcLib == null && failure != null) throw failure;
            return fromLrcLib;
        }
        Lyrics chosen = prefer(fromLrcLib, spotify.find(track));
        if (chosen == null && failure != null) throw failure;
        return chosen;
    }

    @Nullable
    static Lyrics prefer(@Nullable Lyrics fromLrcLib, @Nullable Lyrics fromSpotify) {
        if (fromLrcLib != null && (fromLrcLib.hasSynced() || fromLrcLib.instrumental())) return fromLrcLib;
        if (fromSpotify != null && fromSpotify.hasSynced()) return fromSpotify;
        return fromLrcLib != null ? fromLrcLib : fromSpotify;
    }
}
