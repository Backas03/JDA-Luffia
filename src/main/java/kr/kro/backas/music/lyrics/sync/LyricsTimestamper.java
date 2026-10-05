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
    static final double SKIP_PENALTY = 0.35;
    static final int MIN_MATCHED = 4;
    static final double MIN_MATCHED_RATIO = 0.4;
    static final long MIN_GAP_MS = 200;
    static final long FILL_GAP_MS = 3_000;
    static final long TAIL_MARGIN_MS = 1_000;
    static final long LINE_LEAD_MS = 300;
    static final long OUTLIER_MS = 2_500;
    private static final double NONE = Double.NEGATIVE_INFINITY;
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
        int tokens = tokenIndex.size();
        int count = lines.size();

        double[][] score = new double[count][tokens];
        for (int i = 0; i < count; i++) {
            String target = LyricsAligner.normalize(lines.get(i));
            for (int j = 0; j < tokens; j++) {
                if (target.length() < 2) {
                    score[i][j] = NONE;
                    continue;
                }
                int from = tokenIndex.get(j);
                int to = Math.min(text.length(), from + target.length());
                double similarity = to - from < target.length() * 0.6 ? 0 : LyricsAligner.similarity(text.substring(from, to), target);
                score[i][j] = similarity >= MIN_SCORE ? similarity : NONE;
            }
        }

        double[][] dp = new double[count][tokens];
        int[][] previousLine = new int[count][tokens];
        int[][] previousToken = new int[count][tokens];
        double[] carry = new double[tokens + 1];
        int[] carryLine = new int[tokens + 1];
        int[] carryToken = new int[tokens + 1];
        java.util.Arrays.fill(carryLine, -1);
        java.util.Arrays.fill(carryToken, -1);
        for (int i = 0; i < count; i++) {
            double[] nextCarry = new double[tokens + 1];
            int[] nextCarryLine = new int[tokens + 1];
            int[] nextCarryToken = new int[tokens + 1];
            double prefix = NONE;
            int prefixToken = -1;
            for (int j = 0; j < tokens; j++) {
                double base = i == 0 ? 0 : carry[j];
                if (score[i][j] == NONE || base == NONE) {
                    dp[i][j] = NONE;
                } else {
                    dp[i][j] = base + score[i][j];
                    previousLine[i][j] = i == 0 ? -1 : carryLine[j];
                    previousToken[i][j] = i == 0 ? -1 : carryToken[j];
                }
                if (dp[i][j] > prefix) {
                    prefix = dp[i][j];
                    prefixToken = j;
                }
                double skipped = i == 0 ? -SKIP_PENALTY : (carry[j + 1] == NONE ? NONE : carry[j + 1] - SKIP_PENALTY);
                if (prefix >= skipped) {
                    nextCarry[j + 1] = prefix;
                    nextCarryLine[j + 1] = prefixToken < 0 ? -1 : i;
                    nextCarryToken[j + 1] = prefixToken;
                } else {
                    nextCarry[j + 1] = skipped;
                    nextCarryLine[j + 1] = i == 0 ? -1 : carryLine[j + 1];
                    nextCarryToken[j + 1] = i == 0 ? -1 : carryToken[j + 1];
                }
            }
            nextCarry[0] = i == 0 ? -SKIP_PENALTY : (carry[0] == NONE ? NONE : carry[0] - SKIP_PENALTY);
            nextCarryLine[0] = -1;
            nextCarryToken[0] = -1;
            carry = nextCarry;
            carryLine = nextCarryLine;
            carryToken = nextCarryToken;
        }

        double best = NONE;
        int bestLine = -1;
        int bestToken = -1;
        for (int i = 0; i < count; i++) {
            for (int j = 0; j < tokens; j++) {
                if (dp[i][j] == NONE) continue;
                double total = dp[i][j] - SKIP_PENALTY * (count - 1 - i);
                if (total > best) {
                    best = total;
                    bestLine = i;
                    bestToken = j;
                }
            }
        }
        if (bestLine < 0) return Optional.empty();

        long[] times = new long[count];
        boolean[] matched = new boolean[count];
        int matchedCount = 0;
        int line = bestLine;
        int token = bestToken;
        while (line >= 0 && token >= 0) {
            times[line] = LyricsAligner.onsetMs(tokenWords.get(token));
            matched[line] = true;
            matchedCount++;
            int nextLine = previousLine[line][token];
            int nextToken = previousToken[line][token];
            line = nextLine;
            token = nextToken;
        }
        if (matchedCount < MIN_MATCHED || matchedCount < count * MIN_MATCHED_RATIO) return Optional.empty();

        matchedCount -= dropOutliers(times, matched);
        for (int i = 0; i < count; i++) {
            if (matched[i]) times[i] = Math.max(0, times[i] - LINE_LEAD_MS);
        }
        fillGaps(times, matched, durationMs);
        List<LyricLine> result = new ArrayList<>(count);
        for (int i = 0; i < count; i++) result.add(new LyricLine(times[i], lines.get(i)));
        return Optional.of(new Result(result, matchedCount, count));
    }

    static int dropOutliers(long[] times, boolean[] matched) {
        int dropped = 0;
        for (int i = 0; i < times.length; i++) {
            if (!matched[i]) continue;
            int before = i - 1;
            while (before >= 0 && !matched[before]) before--;
            int after = i + 1;
            while (after < times.length && !matched[after]) after++;
            if (before < 0 || after >= times.length) continue;
            long expected = times[before] + (times[after] - times[before]) * (i - before) / (after - before);
            boolean ordered = times[i] > times[before] && times[i] < times[after];
            if (!ordered || Math.abs(times[i] - expected) > OUTLIER_MS) {
                matched[i] = false;
                dropped++;
            }
        }
        return dropped;
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
