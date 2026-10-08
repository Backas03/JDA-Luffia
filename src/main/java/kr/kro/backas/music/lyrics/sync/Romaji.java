package kr.kro.backas.music.lyrics.sync;

import com.atilika.kuromoji.ipadic.Token;
import com.atilika.kuromoji.ipadic.Tokenizer;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class Romaji {

    static final double MIN_ROMAJI_SHARE = 0.8;
    static final int MIN_LETTERS = 40;
    private static final Pattern WORD = Pattern.compile("[a-z]+");
    private static final Pattern SYLLABLES = Pattern.compile(
            "(?:(?:ch|sh|ts|[kgsztdnhbpmyrwfjv]y?)?[aiueo]|n|m(?=[bpm])|k(?=k)|g(?=g)|s(?=s)|z(?=z)|t(?=[tc])|d(?=d)"
                    + "|h(?=h)|b(?=b)|p(?=p)|c(?=c)|f(?=f)|j(?=j)|r(?=r))+");
    private static final String SMALL = "ぁぃぅぇぉゃゅょゎ";
    private static final Map<Character, String> KANA = kanaTable();
    private static volatile Tokenizer tokenizer;

    private Romaji() {
    }

    public static boolean looksLike(List<String> lines) {
        int letters = 0;
        int romaji = 0;
        for (String line : lines) {
            if (line == null) continue;
            String folded = fold(line);
            for (int i = 0; i < folded.length(); ) {
                int cp = folded.codePointAt(i);
                i += Character.charCount(cp);
                if (Character.isLetter(cp) && Character.UnicodeScript.of(cp) != Character.UnicodeScript.LATIN) return false;
            }
            Matcher matcher = WORD.matcher(folded);
            while (matcher.find()) {
                String word = matcher.group();
                letters += word.length();
                if (SYLLABLES.matcher(word).matches()) romaji += word.length();
            }
        }
        return letters >= MIN_LETTERS && romaji >= letters * MIN_ROMAJI_SHARE;
    }

    public static List<String> lines(List<String> lines) {
        List<String> converted = new ArrayList<>(lines.size());
        for (String line : lines) converted.add(line == null ? "" : canonical(line));
        return converted;
    }

    public static List<WhisperClient.Word> words(List<WhisperClient.Word> words) {
        StringBuilder text = new StringBuilder();
        int[] starts = new int[words.size()];
        for (int i = 0; i < words.size(); i++) {
            String word = words.get(i).text();
            if (!text.isEmpty() && !word.isEmpty() && isAsciiLetter(text.charAt(text.length() - 1)) && isAsciiLetter(word.charAt(0))) {
                text.append(' ');
            }
            starts[i] = text.length();
            text.append(word);
        }
        StringBuilder[] readings = new StringBuilder[words.size()];
        for (int i = 0; i < readings.length; i++) readings[i] = new StringBuilder();
        if (!text.isEmpty()) {
            for (Token token : tokenizer().tokenize(text.toString())) {
                readings[owner(starts, token.getPosition())].append(read(token));
            }
        }
        List<WhisperClient.Word> converted = new ArrayList<>(words.size());
        for (int i = 0; i < words.size(); i++) {
            String reading = canonical(readings[i].toString());
            if (reading.isEmpty()) continue;
            WhisperClient.Word word = words.get(i);
            converted.add(new WhisperClient.Word(reading, word.startMs(), word.endMs()));
        }
        return converted;
    }

    static String canonical(String text) {
        String romaji = fold(text).replaceAll("[^a-z]", "");
        romaji = romaji.replaceAll("sy([auo])", "sh$1")
                .replaceAll("ty([auo])", "ch$1")
                .replaceAll("(?:zy|jy|dy)([auo])", "j$1")
                .replace("si", "shi")
                .replace("ti", "chi")
                .replace("tu", "tsu")
                .replaceAll("zi|di", "ji")
                .replace("du", "zu")
                .replaceAll("(?<![sc])hu", "fu")
                .replace("tch", "cch")
                .replaceAll("m(?=[bpm])", "n")
                .replace("wo", "o")
                .replace("ou", "o")
                .replace("ei", "e");
        return romaji.replaceAll("([aiueo])\\1+", "$1");
    }

    static String fromKana(String text) {
        String normalized = Normalizer.normalize(text, Normalizer.Form.NFKC);
        StringBuilder out = new StringBuilder();
        boolean geminate = false;
        for (int i = 0; i < normalized.length(); i++) {
            char c = hiragana(normalized.charAt(i));
            if (c == 'っ') {
                geminate = true;
                continue;
            }
            if (c == 'ー') {
                char last = out.isEmpty() ? 0 : out.charAt(out.length() - 1);
                if (last != 0 && "aiueo".indexOf(last) >= 0) out.append(last);
                continue;
            }
            String syllable = KANA.get(c);
            if (syllable == null) {
                if (c < 0x80 && Character.isLetterOrDigit(c)) out.append(Character.toLowerCase(c));
                geminate = false;
                continue;
            }
            char next = i + 1 < normalized.length() ? hiragana(normalized.charAt(i + 1)) : 0;
            if (SMALL.indexOf(c) < 0 && next != 0 && SMALL.indexOf(next) >= 0) {
                syllable = combine(syllable, KANA.get(next));
                i++;
            }
            if (geminate && "aiueon".indexOf(syllable.charAt(0)) < 0) out.append(syllable.charAt(0));
            geminate = false;
            out.append(syllable);
        }
        return out.toString();
    }

    private static String combine(String base, String small) {
        String stem;
        if (base.equals("u")) stem = "w";
        else if (base.equals("i")) stem = "y";
        else if (base.length() > 1) stem = base.substring(0, base.length() - 1);
        else stem = base;
        if (small.startsWith("y") && (stem.endsWith("sh") || stem.endsWith("ch") || stem.equals("j"))) return stem + small.substring(1);
        return stem + small;
    }

    private static String read(Token token) {
        String pronunciation = token.getPronunciation();
        boolean known = pronunciation != null && !pronunciation.isEmpty() && !pronunciation.equals("*");
        return fromKana(known ? pronunciation : token.getSurface());
    }

    private static int owner(int[] starts, int position) {
        int index = Arrays.binarySearch(starts, position);
        return index >= 0 ? index : Math.max(0, -index - 2);
    }

    private static Tokenizer tokenizer() {
        Tokenizer current = tokenizer;
        if (current == null) {
            synchronized (Romaji.class) {
                current = tokenizer;
                if (current == null) {
                    current = new Tokenizer();
                    tokenizer = current;
                }
            }
        }
        return current;
    }

    private static String fold(String text) {
        return Normalizer.normalize(text, Normalizer.Form.NFKD).replaceAll("\\p{M}", "").replace("'", "").toLowerCase(Locale.ROOT);
    }

    private static boolean isAsciiLetter(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z');
    }

    private static char hiragana(char c) {
        return c >= 0x30A1 && c <= 0x30F6 ? (char) (c - 0x60) : c;
    }

    private static Map<Character, String> kanaTable() {
        String[][] rows = {
                {"あいうえお", "a", "i", "u", "e", "o"},
                {"かきくけこ", "ka", "ki", "ku", "ke", "ko"},
                {"がぎぐげご", "ga", "gi", "gu", "ge", "go"},
                {"さしすせそ", "sa", "shi", "su", "se", "so"},
                {"ざじずぜぞ", "za", "ji", "zu", "ze", "zo"},
                {"たちつてと", "ta", "chi", "tsu", "te", "to"},
                {"だぢづでど", "da", "ji", "zu", "de", "do"},
                {"なにぬねの", "na", "ni", "nu", "ne", "no"},
                {"はひふへほ", "ha", "hi", "fu", "he", "ho"},
                {"ばびぶべぼ", "ba", "bi", "bu", "be", "bo"},
                {"ぱぴぷぺぽ", "pa", "pi", "pu", "pe", "po"},
                {"まみむめも", "ma", "mi", "mu", "me", "mo"},
                {"らりるれろ", "ra", "ri", "ru", "re", "ro"},
                {"ぁぃぅぇぉ", "a", "i", "u", "e", "o"},
                {"やゆよわを", "ya", "yu", "yo", "wa", "o"},
                {"ゃゅょゎん", "ya", "yu", "yo", "wa", "n"},
                {"ゐゑゔ", "i", "e", "vu"}};
        Map<Character, String> table = new HashMap<>();
        for (String[] row : rows) {
            for (int i = 0; i < row[0].length(); i++) table.put(row[0].charAt(i), row[i + 1]);
        }
        return Map.copyOf(table);
    }
}
