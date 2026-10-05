package kr.kro.backas.music.lyrics;

import org.jetbrains.annotations.Nullable;

import java.util.List;

public record Lyrics(String trackName,
                     String artistName,
                     @Nullable String plain,
                     List<LyricLine> synced,
                     boolean instrumental,
                     boolean aiTimed) {

    public Lyrics(String trackName, String artistName, @Nullable String plain, List<LyricLine> synced, boolean instrumental) {
        this(trackName, artistName, plain, synced, instrumental, false);
    }

    public boolean hasSynced() {
        return synced != null && !synced.isEmpty();
    }

    public boolean hasPlain() {
        return plain != null && !plain.isBlank();
    }

    public Lyrics withAiTimestamps(List<LyricLine> lines) {
        return new Lyrics(trackName, artistName, plain, lines, instrumental, true);
    }
}
