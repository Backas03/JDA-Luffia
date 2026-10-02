package kr.kro.backas.music.lyrics;

import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class LyricsRepetition {

    private static final Pattern TOKEN = Pattern.compile("[^\\s\\u3000,、，]+");
    private static final Pattern SEPARATOR = Pattern.compile("[,、，]");
    private static final int MIN_GLUED_SOURCE_RUN = 3;
    private static final int MAX_RUN = 40;

    private record Token(int start, String core) {
    }

    private record Run(int first, int length) {
    }

    private record Phrases(int count, boolean exact) {
    }

    private LyricsRepetition() {
    }

    static String match(@Nullable String source, @Nullable String translated) {
        if (source == null || translated == null || translated.isBlank()) return translated;
        String byPhrases = matchPhrases(source, translated);
        if (byPhrases != null) return byPhrases;
        String byTokens = matchTokens(source, translated);
        if (byTokens != null) return byTokens;
        String glued = matchGlued(source, translated);
        return glued == null ? translated : glued;
    }

    @Nullable
    private static String matchPhrases(String source, String translated) {
        List<Token> tokens = tokens(translated);
        if (tokens.size() < 2 || tokens.get(0).core().isEmpty()) return null;
        for (Token token : tokens) {
            if (!token.core().equals(tokens.get(0).core())) return null;
        }
        Phrases phrases = phrases(source);
        if (phrases.count() < 2 || phrases.count() > MAX_RUN) return null;
        if (tokens.size() == phrases.count() || (!phrases.exact() && tokens.size() > phrases.count())) return translated;
        return resize(translated, tokens, new Run(0, tokens.size()), phrases.count());
    }

    private static Phrases phrases(String source) {
        List<Token> tokens = tokens(source);
        boolean identical = tokens.size() >= 2 && !tokens.get(0).core().isEmpty();
        for (Token token : tokens) {
            if (!token.core().equals(tokens.get(0).core())) identical = false;
        }
        if (identical) return new Phrases(tokens.size(), true);
        if (SEPARATOR.matcher(source).find()) {
            int parts = 0;
            for (String part : SEPARATOR.split(source)) {
                if (!core(part).isEmpty()) parts++;
            }
            return new Phrases(parts, true);
        }
        int count = 0;
        int first = 0;
        for (int i = 1; i <= tokens.size(); i++) {
            if (i < tokens.size() && script(tokens.get(i).core()) == script(tokens.get(first).core())) continue;
            boolean repeated = i - first >= 2 && !tokens.get(first).core().isEmpty();
            for (int j = first; j < i; j++) {
                if (!tokens.get(j).core().equals(tokens.get(first).core())) repeated = false;
            }
            if (script(tokens.get(first).core()) != 0) count += repeated ? i - first : 1;
            first = i;
        }
        return new Phrases(count, false);
    }

    private static int script(String core) {
        if (core.isEmpty()) return 0;
        char c = core.charAt(0);
        if ((c >= 'a' && c <= 'z') || (c >= 0x00C0 && c <= 0x024F)) return 1;
        if ((c >= 0x3040 && c <= 0x30FF) || (c >= 0x4E00 && c <= 0x9FFF)) return 2;
        if ((c >= 0xAC00 && c <= 0xD7A3) || (c >= 0x1100 && c <= 0x11FF) || (c >= 0x3130 && c <= 0x318F)) return 3;
        if (c >= 0x0400 && c <= 0x04FF) return 4;
        return Character.isLetter(c) ? 5 : 0;
    }

    private static String resize(String translated, List<Token> tokens, Run run, int wanted) {
        int firstStart = tokens.get(run.first()).start();
        String step = translated.substring(firstStart, tokens.get(run.first() + 1).start());
        int lastStart = tokens.get(run.first() + run.length() - 1).start();
        return translated.substring(0, firstStart) + step.repeat(wanted - 1) + translated.substring(lastStart);
    }

    @Nullable
    private static String matchTokens(String source, String translated) {
        List<Token> sourceTokens = tokens(source);
        List<Run> sourceRuns = runs(sourceTokens);
        if (sourceRuns.size() != 1) return null;
        int wanted = sourceRuns.get(0).length();
        if (wanted > MAX_RUN) return null;
        List<Token> tokens = tokens(translated);
        List<Run> translatedRuns = runs(tokens);
        if (translatedRuns.isEmpty()) {
            if (tokens.size() != 1 || wanted != sourceTokens.size() || tokens.get(0).core().isEmpty()) return null;
            String unit = tokens.get(0).core();
            return translated.substring(0, tokens.get(0).start()) + (unit + " ").repeat(wanted - 1)
                    + translated.substring(tokens.get(0).start());
        }
        if (translatedRuns.size() != 1) return null;
        Run run = translatedRuns.get(0);
        if (run.length() == wanted) return translated;
        return resize(translated, tokens, run, wanted);
    }

    private static List<Token> tokens(String text) {
        List<Token> tokens = new ArrayList<>();
        Matcher matcher = TOKEN.matcher(text);
        while (matcher.find()) tokens.add(new Token(matcher.start(), core(matcher.group())));
        return tokens;
    }

    private static String core(String token) {
        StringBuilder core = new StringBuilder();
        for (int i = 0; i < token.length(); i++) {
            char c = token.charAt(i);
            if (Character.isLetterOrDigit(c)) core.append(c);
        }
        return core.toString().toLowerCase(Locale.ROOT);
    }

    private static List<Run> runs(List<Token> tokens) {
        List<Run> runs = new ArrayList<>();
        int first = 0;
        for (int i = 1; i <= tokens.size(); i++) {
            boolean same = i < tokens.size() && !tokens.get(i).core().isEmpty()
                    && tokens.get(i).core().equals(tokens.get(first).core());
            if (same) continue;
            if (i - first >= 2) runs.add(new Run(first, i - first));
            first = i;
        }
        return runs;
    }

    @Nullable
    private static String matchGlued(String source, String translated) {
        List<Run> sourceRuns = letterRuns(source, MIN_GLUED_SOURCE_RUN);
        if (sourceRuns.size() != 1) return null;
        int wanted = sourceRuns.get(0).length();
        if (wanted > MAX_RUN) return null;
        List<Run> translatedRuns = letterRuns(translated, 2);
        if (translatedRuns.size() != 1) return null;
        Run run = translatedRuns.get(0);
        if (run.length() == wanted) return translated;
        String unit = String.valueOf(translated.charAt(run.first()));
        return translated.substring(0, run.first()) + unit.repeat(wanted) + translated.substring(run.first() + run.length());
    }

    private static List<Run> letterRuns(String text, int minimum) {
        List<Run> runs = new ArrayList<>();
        int first = 0;
        for (int i = 1; i <= text.length(); i++) {
            if (i < text.length() && text.charAt(i) == text.charAt(first)) continue;
            if (i - first >= minimum && Character.isLetter(text.charAt(first))) runs.add(new Run(first, i - first));
            first = i;
        }
        return runs;
    }
}
