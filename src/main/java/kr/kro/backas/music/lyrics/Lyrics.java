package kr.kro.backas.music.lyrics;

import org.jetbrains.annotations.Nullable;

import java.util.List;

public record Lyrics(String trackName,
                     String artistName,
                     @Nullable String plain,
                     List<LyricLine> synced,
                     boolean instrumental,
                     boolean aiTimed,
                     boolean aiGenerated,
                     @Nullable Source source) {

    public enum Source {
        LRCLIB("LRCLIB"),
        SPOTIFY("Spotify"),
        AI("AI");

        private final String label;

        Source(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    public Lyrics(String trackName, String artistName, @Nullable String plain, List<LyricLine> synced, boolean instrumental) {
        this(trackName, artistName, plain, synced, instrumental, false, false, null);
    }

    public Lyrics(String trackName, String artistName, @Nullable String plain, List<LyricLine> synced, boolean instrumental, boolean aiTimed) {
        this(trackName, artistName, plain, synced, instrumental, aiTimed, false, null);
    }

    public boolean hasSynced() {
        return synced != null && !synced.isEmpty();
    }

    public boolean hasPlain() {
        return plain != null && !plain.isBlank();
    }

    public Lyrics withAiTimestamps(List<LyricLine> lines) {
        return new Lyrics(trackName, artistName, plain, lines, instrumental, true, aiGenerated, source);
    }

    public Lyrics withSource(Source source) {
        return new Lyrics(trackName, artistName, plain, synced, instrumental, aiTimed, aiGenerated, source);
    }

    public Lyrics withoutSync() {
        return new Lyrics(trackName, artistName, plain, List.of(), instrumental, false, false, source);
    }
}
