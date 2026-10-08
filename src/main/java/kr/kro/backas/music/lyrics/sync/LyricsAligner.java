package kr.kro.backas.music.lyrics.sync;

import kr.kro.backas.music.lyrics.LyricLine;
import org.jetbrains.annotations.Nullable;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

public final class LyricsAligner {

    public record Alignment(long offsetMs, int matchedLines, long spreadMs) {
    }

    static final double MIN_SCORE = 0.45;
    static final int MIN_MATCHES = 3;
    static final long MAX_DEVIATION_MS = 700;
    static final int MIN_LINE_CHARS = 4;
    static final long EDGE_MARGIN_MS = 1500;
    static final long OFFSET_SLACK_MS = 30_000;
    static final int MAX_LINES = 12;
    static final long LATIN_CHAR_MS = 110;
    static final long CJK_CHAR_MS = 320;
    static final long MIN_WORD_MS = 250;

    private LyricsAligner() {
    }

    public static Optional<Alignment> align(List<WhisperClient.Word> words, List<LyricLine> lines, long capturedMs) {
        List<String> texts = texts(lines);
        if (Romaji.looksLike(texts)) {
            List<String> romanized = Romaji.lines(texts);
            List<LyricLine> converted = new ArrayList<>(lines.size());
            for (int i = 0; i < lines.size(); i++) converted.add(new LyricLine(lines.get(i).timeMs(), romanized.get(i)));
            lines = converted;
            words = Romaji.words(words);
        }
        List<Integer> tokenIndex = new ArrayList<>();
        List<WhisperClient.Word> tokenWords = new ArrayList<>();
        StringBuilder transcript = new StringBuilder();
        for (WhisperClient.Word word : words) {
            String normalized = normalize(word.text());
            if (normalized.isEmpty()) continue;
            tokenIndex.add(transcript.length());
            tokenWords.add(word);
            transcript.append(normalized);
        }
        if (transcript.length() < MIN_LINE_CHARS) return Optional.empty();
        String text = transcript.toString();

        List<Long> eligibleTimes = new ArrayList<>();
        List<List<Candidate>> candidates = new ArrayList<>();
        for (int i = 0; i < lines.size() && eligibleTimes.size() < MAX_LINES; i++) {
            LyricLine line = lines.get(i);
            if (line.timeMs() > capturedMs + OFFSET_SLACK_MS) break;
            String target = normalize(line.text());
            if (target.length() < MIN_LINE_CHARS) continue;
            eligibleTimes.add(line.timeMs());
            List<Candidate> found = occurrences(text, tokenIndex, tokenWords, target, line.timeMs());
            if (!found.isEmpty()) candidates.add(found);
        }

        List<Long> centers = new ArrayList<>();
        List<Support> supports = new ArrayList<>();
        int best = -1;
        for (List<Candidate> found : candidates) {
            for (Candidate candidate : found) {
                Support support = support(candidates, candidate.offsetMs());
                centers.add(candidate.offsetMs());
                supports.add(support);
                if (best < 0 || support.beats(supports.get(best))) best = supports.size() - 1;
            }
        }
        if (best < 0 || supports.get(best).offsets().size() < 2) return Optional.empty();
        int bestCount = supports.get(best).offsets().size();
        for (int i = 0; i < supports.size(); i++) {
            if (supports.get(i).offsets().size() == bestCount && Math.abs(centers.get(i) - centers.get(best)) > 2 * MAX_DEVIATION_MS) {
                return Optional.empty();
            }
        }

        List<Long> offsets = new ArrayList<>(supports.get(best).offsets());
        offsets.sort(Long::compare);
        long median = median(offsets);
        List<Long> agreeing = new ArrayList<>();
        for (long offset : offsets) {
            if (Math.abs(offset - median) <= MAX_DEVIATION_MS) agreeing.add(offset);
        }
        int expectedInCapture = 0;
        for (long time : eligibleTimes) {
            long expectedAt = time - median;
            if (expectedAt >= 0 && expectedAt <= capturedMs - EDGE_MARGIN_MS) expectedInCapture++;
        }
        int required = Math.max(2, Math.min(MIN_MATCHES, expectedInCapture));
        if (agreeing.size() < required) return Optional.empty();
        long refined = median(agreeing);
        long spread = agreeing.get(agreeing.size() - 1) - agreeing.get(0);
        return Optional.of(new Alignment(Math.round(refined / 10.0) * 10, agreeing.size(), spread));
    }

    private record Candidate(long offsetMs, double score) {
    }

    private record Support(List<Long> offsets, double score) {
        boolean beats(Support other) {
            if (offsets.size() != other.offsets.size()) return offsets.size() > other.offsets.size();
            return score > other.score;
        }
    }

    private static List<Candidate> occurrences(String text, List<Integer> tokenIndex, List<WhisperClient.Word> tokenWords,
                                               String target, long lineMs) {
        double[] scores = new double[tokenIndex.size()];
        int scored = 0;
        for (int token = 0; token < tokenIndex.size(); token++) {
            int from = tokenIndex.get(token);
            int to = Math.min(text.length(), from + target.length());
            if (to - from < target.length() * 0.6) break;
            scores[token] = similarity(text.substring(from, to), target);
            scored++;
        }
        List<Candidate> found = new ArrayList<>();
        for (int token = 0; token < scored; token++) {
            if (scores[token] < MIN_SCORE || !isPeak(scores, scored, tokenIndex, token, target.length())) continue;
            found.add(new Candidate(lineMs - onsetMs(tokenWords.get(token)), scores[token]));
        }
        return found;
    }

    private static boolean isPeak(double[] scores, int scored, List<Integer> tokenIndex, int token, int radius) {
        int at = tokenIndex.get(token);
        for (int other = token - 1; other >= 0 && at - tokenIndex.get(other) < radius; other--) {
            if (scores[other] >= scores[token]) return false;
        }
        for (int other = token + 1; other < scored && tokenIndex.get(other) - at < radius; other++) {
            if (scores[other] > scores[token]) return false;
        }
        return true;
    }

    private static Support support(List<List<Candidate>> candidates, long centerMs) {
        List<Long> offsets = new ArrayList<>();
        double total = 0;
        for (List<Candidate> found : candidates) {
            Candidate pick = null;
            for (Candidate candidate : found) {
                if (Math.abs(candidate.offsetMs() - centerMs) > MAX_DEVIATION_MS) continue;
                if (pick == null || candidate.score() > pick.score()) pick = candidate;
            }
            if (pick == null) continue;
            offsets.add(pick.offsetMs());
            total += pick.score();
        }
        return new Support(offsets, total);
    }

    static long onsetMs(WhisperClient.Word word) {
        return Math.max(word.startMs(), word.endMs() - plausibleDurationMs(word.text()));
    }

    static long plausibleDurationMs(String text) {
        long total = 0;
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            i += Character.charCount(cp);
            if (!Character.isLetterOrDigit(cp)) continue;
            total += cp < 0x3000 ? LATIN_CHAR_MS : CJK_CHAR_MS;
        }
        return Math.max(MIN_WORD_MS, total);
    }

    private static long median(List<Long> sorted) {
        int size = sorted.size();
        return size % 2 == 1 ? sorted.get(size / 2) : (sorted.get(size / 2 - 1) + sorted.get(size / 2)) / 2;
    }

    static String normalize(String text) {
        String folded = Normalizer.normalize(text, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
        StringBuilder out = new StringBuilder(folded.length());
        for (int i = 0; i < folded.length(); ) {
            int cp = folded.codePointAt(i);
            i += Character.charCount(cp);
            if (!Character.isLetterOrDigit(cp)) continue;
            if (cp >= 0x30A1 && cp <= 0x30F6) cp -= 0x60;
            out.appendCodePoint(cp);
        }
        return out.toString();
    }

    static double similarity(String a, String b) {
        if (a.length() < 2 || b.length() < 2) return a.equals(b) ? 1 : 0;
        Map<String, Integer> counts = new HashMap<>();
        for (int i = 0; i + 1 < a.length(); i++) counts.merge(a.substring(i, i + 2), 1, Integer::sum);
        int overlap = 0;
        for (int i = 0; i + 1 < b.length(); i++) {
            String bigram = b.substring(i, i + 2);
            Integer left = counts.get(bigram);
            if (left != null && left > 0) {
                counts.put(bigram, left - 1);
                overlap++;
            }
        }
        return 2.0 * overlap / ((a.length() - 1) + (b.length() - 1));
    }

    @Nullable
    public static String languageHint(List<LyricLine> lines) {
        int kana = 0;
        int hangul = 0;
        int han = 0;
        int latin = 0;
        for (LyricLine line : lines) {
            String text = line.text();
            for (int i = 0; i < text.length(); ) {
                int cp = text.codePointAt(i);
                i += Character.charCount(cp);
                if (cp >= 0x3040 && cp <= 0x30FF) kana++;
                else if ((cp >= 0xAC00 && cp <= 0xD7A3) || (cp >= 0x1100 && cp <= 0x11FF) || (cp >= 0x3130 && cp <= 0x318F)) hangul++;
                else if (cp >= 0x4E00 && cp <= 0x9FFF) han++;
                else if (cp < 0x80 && Character.isLetter(cp)) latin++;
            }
        }
        if (kana > 0 && kana >= hangul) return "ja";
        if (hangul > 0) return "ko";
        if (han > 0) return null;
        if (latin == 0) return null;
        return Romaji.looksLike(texts(lines)) ? "ja" : "en";
    }

    private static List<String> texts(List<LyricLine> lines) {
        List<String> texts = new ArrayList<>(lines.size());
        for (LyricLine line : lines) texts.add(line.text());
        return texts;
    }
}
