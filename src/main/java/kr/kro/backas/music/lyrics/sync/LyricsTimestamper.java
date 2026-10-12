package kr.kro.backas.music.lyrics.sync;

import kr.kro.backas.music.lyrics.LyricLine;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

public final class LyricsTimestamper {

    public record Result(List<LyricLine> lines, int matched, int total) {
    }

    static final double MIN_SCORE = 0.42;
    private static final double[] WINDOW_SCALES = {0.8, 1.0, 1.25};
    static final double SKIP_PENALTY = 0.35;
    static final int MIN_MATCHED = 4;
    static final double MIN_MATCHED_RATIO = 0.4;
    static final long MIN_GAP_MS = 1_000;
    static final long FILL_GAP_MS = 3_000;
    static final long TAIL_MARGIN_MS = 1_000;
    static final long LINE_LEAD_MS = 300;
    static final double OVERLAP_SHARE = 0.8;
    static final double MIN_LINE_SHARE = 0.35;
    static final long PAUSE_MS = 500;
    static final double STRETCH_RATIO = 1.25;
    static final long REST_GAP_MS = 4_000;
    static final long REST_DELAY_MS = 1_000;
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
        return timestamp(lines, words, durationMs, false);
    }

    public static Optional<Result> timestamp(List<String> lines, List<WhisperClient.Word> words, long durationMs, boolean partial) {
        boolean romaji = Romaji.looksLike(lines);
        List<String> targets = romaji ? Romaji.lines(lines) : lines;
        if (romaji) words = Romaji.words(words);
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
        int[][] windowEnd = new int[count][tokens];
        for (int i = 0; i < count; i++) {
            String target = LyricsAligner.normalize(targets.get(i));
            for (int j = 0; j < tokens; j++) {
                if (target.length() < 2) {
                    score[i][j] = NONE;
                    continue;
                }
                int from = tokenIndex.get(j);
                double similarity = 0;
                int end = from;
                for (double scale : WINDOW_SCALES) {
                    int to = Math.min(text.length(), from + (int) Math.round(target.length() * scale));
                    if (to - from < target.length() * 0.6) continue;
                    double candidate = LyricsAligner.similarity(text.substring(from, to), target);
                    if (candidate > similarity) {
                        similarity = candidate;
                        end = to;
                    }
                }
                score[i][j] = similarity >= MIN_SCORE ? similarity : NONE;
                windowEnd[i][j] = end;
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
            double[] released = new double[tokens + 1];
            int[] releasedToken = new int[tokens + 1];
            java.util.Arrays.fill(released, NONE);
            for (int j = 0; j < tokens; j++) {
                double base = i == 0 ? 0 : carry[j];
                if (score[i][j] == NONE || base == NONE) {
                    dp[i][j] = NONE;
                    continue;
                }
                dp[i][j] = base + score[i][j];
                previousLine[i][j] = i == 0 ? -1 : carryLine[j];
                previousToken[i][j] = i == 0 ? -1 : carryToken[j];
                int release = releaseToken(tokenIndex, j, windowEnd[i][j]);
                if (dp[i][j] > released[release]) {
                    released[release] = dp[i][j];
                    releasedToken[release] = j;
                }
            }
            double[] nextCarry = new double[tokens + 1];
            int[] nextCarryLine = new int[tokens + 1];
            int[] nextCarryToken = new int[tokens + 1];
            double prefix = NONE;
            int prefixToken = -1;
            for (int k = 0; k <= tokens; k++) {
                if (released[k] > prefix) {
                    prefix = released[k];
                    prefixToken = releasedToken[k];
                }
                double skipped = i == 0 ? -SKIP_PENALTY : (carry[k] == NONE ? NONE : carry[k] - SKIP_PENALTY);
                if (prefixToken >= 0 && prefix > skipped) {
                    nextCarry[k] = prefix;
                    nextCarryLine[k] = i;
                    nextCarryToken[k] = prefixToken;
                } else {
                    nextCarry[k] = skipped;
                    nextCarryLine[k] = i == 0 ? -1 : carryLine[k];
                    nextCarryToken[k] = i == 0 ? -1 : carryToken[k];
                }
            }
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

        boolean[] matched = new boolean[count];
        int[] firstToken = new int[count];
        int[] lastChar = new int[count];
        int matchedCount = 0;
        int line = bestLine;
        int token = bestToken;
        while (line >= 0 && token >= 0) {
            matched[line] = true;
            firstToken[line] = token;
            lastChar[line] = windowEnd[line][token];
            matchedCount++;
            int nextLine = previousLine[line][token];
            int nextToken = previousToken[line][token];
            line = nextLine;
            token = nextToken;
        }
        long[] times = new long[count];
        long[] ends = new long[count];
        int sharedUntil = 0;
        for (int i = 0; i < count; i++) {
            if (!matched[i]) continue;
            String target = LyricsAligner.normalize(targets.get(i));
            int first = skipSharedStart(text, tokenIndex, firstToken[i], lastChar[i], sharedUntil, target);
            int last = first;
            while (last + 1 < tokens && tokenIndex.get(last + 1) < lastChar[i]) last++;
            times[i] = lineOnsetMs(text, tokenIndex, tokenWords, first, last);
            ends[i] = tokenWords.get(last).endMs();
            sharedUntil = lastChar[i];
        }
        long[] needed = new long[count];
        for (int i = 0; i < count; i++) needed[i] = (long) (LyricsAligner.plausibleDurationMs(lines.get(i)) * MIN_LINE_SHARE);
        matchedCount -= dropCrowdedAnchors(times, matched, needed, durationMs);
        if (matchedCount < MIN_MATCHED || (!partial && matchedCount < count * MIN_MATCHED_RATIO)) return Optional.empty();

        for (int i = 0; i < count; i++) {
            if (matched[i]) times[i] = Math.max(0, times[i] - LINE_LEAD_MS);
        }
        fillGaps(times, matched, durationMs);
        List<LyricLine> result = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            result.add(new LyricLine(times[i], lines.get(i)));
            if (!matched[i]) continue;
            long next = i + 1 < count ? times[i + 1] : durationMs;
            long rest = Math.max(times[i] + MIN_GAP_MS, ends[i] + REST_DELAY_MS);
            if (next - ends[i] >= REST_GAP_MS && rest <= next - MIN_GAP_MS) result.add(new LyricLine(rest, ""));
        }
        return Optional.of(new Result(result, matchedCount, count));
    }

    static int skipSharedStart(String text, List<Integer> tokenIndex, int first, int end, int sharedUntil, String target) {
        while (first + 1 < tokenIndex.size() && tokenIndex.get(first) < sharedUntil && tokenIndex.get(first + 1) < end
                && LyricsAligner.similarity(text.substring(tokenIndex.get(first + 1), end), target)
                > LyricsAligner.similarity(text.substring(tokenIndex.get(first), end), target)) {
            first++;
        }
        return first;
    }

    static long lineOnsetMs(String text, List<Integer> tokenIndex, List<WhisperClient.Word> words, int first, int last) {
        long onset = LyricsAligner.onsetMs(words.get(first));
        long silenceBefore = words.get(first).startMs() - (first == 0 ? 0 : words.get(first - 1).endMs());
        if (silenceBefore < PAUSE_MS) return onset;
        for (int k = first + 1; k <= last && isStretched(words.get(k - 1)); k++) {
            long before = LyricsAligner.plausibleDurationMs(text.substring(tokenIndex.get(first), tokenIndex.get(k)));
            onset = Math.max(onset, LyricsAligner.onsetMs(words.get(k)) - before);
        }
        return onset;
    }

    static boolean isStretched(WhisperClient.Word word) {
        return word.endMs() - word.startMs() >= STRETCH_RATIO * LyricsAligner.plausibleDurationMs(word.text());
    }

    static int releaseToken(List<Integer> tokenIndex, int token, int windowEnd) {
        int from = tokenIndex.get(token);
        int end = from + (int) Math.ceil((windowEnd - from) * OVERLAP_SHARE);
        for (int next = token + 1; next < tokenIndex.size(); next++) {
            if (tokenIndex.get(next) >= end) return next;
        }
        return tokenIndex.size();
    }

    static int dropCrowdedAnchors(long[] times, boolean[] matched, long[] needed, long durationMs) {
        int dropped = 0;
        while (true) {
            int first = -1;
            int second = -1;
            int previous = -1;
            for (int i = 0; i < times.length && first < 0; i++) {
                if (!matched[i]) continue;
                if (previous >= 0 && times[i] - times[previous] < sum(needed, previous, i)) {
                    first = previous;
                    second = i;
                }
                previous = i;
            }
            if (first < 0) return dropped;
            int before = first - 1;
            while (before >= 0 && !matched[before]) before--;
            int after = second + 1;
            while (after < times.length && !matched[after]) after++;
            long slackWithoutFirst = times[second] - (before < 0 ? 0 : times[before]) - sum(needed, Math.max(before, 0), second);
            long slackWithoutSecond = (after < times.length ? times[after] : durationMs) - times[first] - sum(needed, first, after);
            matched[slackWithoutFirst >= slackWithoutSecond ? first : second] = false;
            dropped++;
        }
    }

    private static long sum(long[] values, int from, int to) {
        long total = 0;
        for (int i = from; i < to; i++) total += values[i];
        return total;
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
