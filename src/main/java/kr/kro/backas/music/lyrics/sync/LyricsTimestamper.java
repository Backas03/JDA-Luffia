package kr.kro.backas.music.lyrics.sync;

import kr.kro.backas.music.lyrics.LyricLine;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

public final class LyricsTimestamper {

    public record Result(List<LyricLine> lines, int matched, int total) {
    }

    static final double MIN_SCORE = 0.45;
    static final int MIN_MATCHED = 4;
    static final double MIN_MATCHED_RATIO = 0.5;
    static final long MIN_GAP_MS = 200;
    static final long FILL_GAP_MS = 3_000;
    static final long TAIL_MARGIN_MS = 1_000;
    private static final Pattern SECTION_TAG = Pattern.compile("^\\s*[\\[(].*[\\])]\\s*$");

    private LyricsTimestamper() {
    }

    public static List<String> usableLines(List<String> raw) {
        List<String> lines = new ArrayList<>();
        for (String line : raw) {
            if (line == null) continue;
            String trimmed = line.trim();
            if (trimmed.isEmpty() || SECTION_TAG.matcher(trimmed).matches()) continue;
            lines.add(trimmed);
        }
        return lines;
    }

    public static Optional<Result> timestamp(List<String> lines, List<WhisperClient.Word> words, long durationMs) {
        List<Integer> tokenIndex = new ArrayList<>();
        List<WhisperClient.Word> tokenWords = new ArrayList<>();
        StringBuilder transcript = new StringBuilder();
        for (WhisperClient.Word word : words) {
            String normalized = LyricsAligner.normalize(word.text());
            if (normalized.isEmpty()) continue;
            tokenIndex.add(transcript.length());
            tokenWords.add(word);
            transcript.append(normalized);
        }
        if (lines.isEmpty() || transcript.isEmpty()) return Optional.empty();
        String text = transcript.toString();

        long[] times = new long[lines.size()];
        boolean[] matched = new boolean[lines.size()];
        int lastToken = -1;
        int matchedCount = 0;
        for (int i = 0; i < lines.size(); i++) {
            String target = LyricsAligner.normalize(lines.get(i));
            if (target.length() < 2) continue;
            double bestScore = 0;
            int bestToken = -1;
            for (int token = lastToken + 1; token < tokenIndex.size(); token++) {
                int from = tokenIndex.get(token);
                int to = Math.min(text.length(), from + target.length());
                if (to - from < target.length() * 0.6) break;
                double score = LyricsAligner.similarity(text.substring(from, to), target);
                if (score > bestScore) {
                    bestScore = score;
                    bestToken = token;
                }
            }
            if (bestScore < MIN_SCORE) continue;
            lastToken = bestToken;
            times[i] = LyricsAligner.onsetMs(tokenWords.get(bestToken));
            matched[i] = true;
            matchedCount++;
        }
        if (matchedCount < MIN_MATCHED || matchedCount < lines.size() * MIN_MATCHED_RATIO) return Optional.empty();

        fillGaps(times, matched, durationMs);
        List<LyricLine> result = new ArrayList<>(lines.size());
        for (int i = 0; i < lines.size(); i++) result.add(new LyricLine(times[i], lines.get(i)));
        return Optional.of(new Result(result, matchedCount, lines.size()));
    }

    static void fillGaps(long[] times, boolean[] matched, long durationMs) {
        int first = -1;
        int last = -1;
        for (int i = 0; i < times.length; i++) {
            if (!matched[i]) continue;
            if (first < 0) first = i;
            last = i;
        }
        for (int i = first - 1; i >= 0; i--) {
            times[i] = Math.max(0, times[i + 1] - FILL_GAP_MS);
        }
        int previous = first;
        for (int i = first + 1; i <= last; i++) {
            if (!matched[i]) continue;
            int gap = i - previous;
            for (int k = previous + 1; k < i; k++) {
                times[k] = times[previous] + (times[i] - times[previous]) * (k - previous) / gap;
            }
            previous = i;
        }
        long ceiling = Math.max(times[last] + MIN_GAP_MS, durationMs - TAIL_MARGIN_MS);
        for (int i = last + 1; i < times.length; i++) {
            times[i] = Math.min(ceiling, times[i - 1] + FILL_GAP_MS);
        }
        for (int i = 1; i < times.length; i++) {
            if (times[i] < times[i - 1] + MIN_GAP_MS) times[i] = times[i - 1] + MIN_GAP_MS;
        }
    }
}
