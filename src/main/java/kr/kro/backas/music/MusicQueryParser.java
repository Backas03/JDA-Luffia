package kr.kro.backas.music;

import org.jetbrains.annotations.Nullable;

import java.util.regex.Pattern;

public final class MusicQueryParser {
    private static final Pattern URL_PATTERN = Pattern.compile(
            "^(https?://\\S+|spotify:(track|album|playlist|artist):[A-Za-z0-9]+)$",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern SPOTIFY_PATTERN = Pattern.compile(
            "^(https?://(open|play)\\.spotify\\.com/|https?://spotify\\.link/|spotify:)",
            Pattern.CASE_INSENSITIVE
    );

    private MusicQueryParser() {
    }

    public static ParsedQuery parse(String rawInput, @Nullable Identifier preferredSource) {
        String input = rawInput.trim();

        if (input.length() > 2 && input.startsWith("<") && input.endsWith(">")) {
            input = input.substring(1, input.length() - 1).trim();
        }
        if (URL_PATTERN.matcher(input).matches()) {
            return new ParsedQuery(Identifier.URL, input);
        }
        Identifier source = preferredSource == null || !preferredSource.isSearch()
                ? Identifier.YOUTUBE
                : preferredSource;
        return new ParsedQuery(source, input);
    }

    public static boolean isSpotifyUrl(String input) {
        return SPOTIFY_PATTERN.matcher(input.trim()).find();
    }

    public record ParsedQuery(Identifier identifier, String query) {
        public boolean requiresSpotify() {
            return identifier == Identifier.SPOTIFY
                    || (identifier == Identifier.URL && isSpotifyUrl(query));
        }
    }
}
