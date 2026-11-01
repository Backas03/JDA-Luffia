package kr.kro.backas.music.lyrics;

import com.atilika.kuromoji.ipadic.Token;
import org.jetbrains.annotations.Nullable;

import java.text.Normalizer;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class KoreanPronunciation {

    private static final int FINAL_N = 4;
    private static final int FINAL_S = 19;
    private static final String LONG_VOWELS = "아에아에어에어에오아에에오우어에이우우이이";
    private static final String SEPARATORS = "、。・,.";
    private static final Set<String> AUXILIARIES = Set.of("いる", "いく", "くる", "おく", "しまう", "みる", "あげる", "くれる", "もらう", "ほしい");
    private static final Map<String, String> KANA = kanaTable();

    private KoreanPronunciation() {
    }

    public static boolean appliesTo(List<LyricLine> lines) {
        int kana = 0;
        int han = 0;
        for (LyricLine line : lines) {
            String text = line.text();
            if (text == null) continue;
            for (int i = 0; i < text.length(); i++) {
                char c = text.charAt(i);
                if (c >= 0x3040 && c <= 0x30FA) kana++;
                else if (c >= 0x4E00 && c <= 0x9FFF) han++;
            }
        }
        return kana > 0 && kana * 4 >= han;
    }

    @Nullable
    public static String of(@Nullable String line) {
        if (line == null || !hasJapanese(line)) return null;
        StringBuilder out = new StringBuilder();
        Token previous = null;
        boolean gap = false;
        for (Token token : JapaneseTokenizer.get().tokenize(line)) {
            if ("記号".equals(token.getPartOfSpeechLevel1())) {
                String symbol = Normalizer.normalize(token.getSurface(), Normalizer.Form.NFKC).strip();
                if (token.getPartOfSpeechLevel2().startsWith("括弧")) continue;
                if (!symbol.isEmpty() && !SEPARATORS.contains(symbol)) out.append(symbol.replace('〜', '~'));
                gap = true;
                continue;
            }
            if (!out.isEmpty() && (gap || !attaches(token, previous))) out.append(' ');
            appendHangul(out, readingOf(token));
            previous = token;
            gap = false;
        }
        String result = out.toString().replaceAll("\\s+", " ").strip();
        return containsHangul(result) ? result : null;
    }

    static void appendHangul(StringBuilder out, String kana) {
        String text = Normalizer.normalize(kana, Normalizer.Form.NFKC);
        for (int i = 0; i < text.length(); i++) {
            char c = hiragana(text.charAt(i));
            if (c == 'っ') {
                addFinal(out, FINAL_S);
                continue;
            }
            if (c == 'ん') {
                if (!addFinal(out, FINAL_N)) out.append('응');
                continue;
            }
            if (c == 'ー') {
                lengthen(out);
                continue;
            }
            if (i + 1 < text.length()) {
                String pair = KANA.get(new String(new char[] {c, hiragana(text.charAt(i + 1))}));
                if (pair != null) {
                    out.append(pair);
                    i++;
                    continue;
                }
            }
            String single = KANA.get(String.valueOf(c));
            out.append(single != null ? single : String.valueOf(text.charAt(i)));
        }
    }

    private static boolean attaches(Token token, @Nullable Token previous) {
        if (previous == null) return true;
        String pos = token.getPartOfSpeechLevel1();
        String detail = token.getPartOfSpeechLevel2();
        String base = token.getBaseForm();
        if ("助詞".equals(pos) || "助動詞".equals(pos) || "接尾".equals(detail)) return true;
        if ("接頭詞".equals(previous.getPartOfSpeechLevel1())) return true;
        boolean predicate = "動詞".equals(pos) || "形容詞".equals(pos);
        if (predicate && "非自立".equals(detail)) return true;
        String previousPos = previous.getPartOfSpeechLevel1();
        if ("ない".equals(base) && "動詞".equals(previousPos)) return true;
        if ("動詞".equals(pos) && "名詞".equals(previousPos) && ("する".equals(base) || "できる".equals(base))) return true;
        if ("名詞".equals(pos)) {
            if ("非自立".equals(detail) && ("ん".equals(token.getSurface()) || "の".equals(token.getSurface()))) return true;
            if ("名詞".equals(previousPos) && !"代名詞".equals(detail) && !"代名詞".equals(previous.getPartOfSpeechLevel2())) return true;
        }
        return predicate && AUXILIARIES.contains(base) && isTe(previous);
    }

    private static boolean isTe(Token token) {
        String surface = token.getSurface();
        if (token.isUser()) return surface.endsWith("て") || surface.endsWith("で");
        return "助詞".equals(token.getPartOfSpeechLevel1()) && "接続助詞".equals(token.getPartOfSpeechLevel2())
                && ("て".equals(surface) || "で".equals(surface));
    }

    private static String readingOf(Token token) {
        String pronunciation = token.getPronunciation();
        if (usable(pronunciation)) return pronunciation;
        String reading = token.getReading();
        if (usable(reading)) return reading;
        return token.getSurface();
    }

    private static boolean usable(@Nullable String value) {
        return value != null && !value.isEmpty() && !value.equals("*");
    }

    private static boolean addFinal(StringBuilder out, int consonant) {
        if (out.isEmpty()) return false;
        char last = out.charAt(out.length() - 1);
        if (!isSyllable(last) || (last - 0xAC00) % 28 != 0) return false;
        out.setCharAt(out.length() - 1, (char) (last + consonant));
        return true;
    }

    private static void lengthen(StringBuilder out) {
        if (out.isEmpty()) return;
        char last = out.charAt(out.length() - 1);
        if (!isSyllable(last) || (last - 0xAC00) % 28 != 0) return;
        out.append(LONG_VOWELS.charAt(((last - 0xAC00) / 28) % 21));
    }

    private static boolean isSyllable(char c) {
        return c >= 0xAC00 && c <= 0xD7A3;
    }

    private static boolean hasJapanese(String text) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if ((c >= 0x3040 && c <= 0x30FA) || (c >= 0x4E00 && c <= 0x9FFF) || c == 0x3005) return true;
        }
        return false;
    }

    private static boolean containsHangul(String text) {
        for (int i = 0; i < text.length(); i++) {
            if (isSyllable(text.charAt(i))) return true;
        }
        return false;
    }

    private static char hiragana(char c) {
        return c >= 0x30A1 && c <= 0x30F6 ? (char) (c - 0x60) : c;
    }

    private static Map<String, String> kanaTable() {
        String[][] rows = {
                {"あいうえお", "아이우에오"},
                {"かきくけこ", "카키쿠케코"},
                {"がぎぐげご", "가기구게고"},
                {"さしすせそ", "사시스세소"},
                {"ざじずぜぞ", "자지즈제조"},
                {"たちつてと", "타치츠테토"},
                {"だぢづでど", "다지즈데도"},
                {"なにぬねの", "나니누네노"},
                {"はひふへほ", "하히후헤호"},
                {"ばびぶべぼ", "바비부베보"},
                {"ぱぴぷぺぽ", "파피푸페포"},
                {"まみむめも", "마미무메모"},
                {"やゆよ", "야유요"},
                {"らりるれろ", "라리루레로"},
                {"わゐゑをゔゕゖ", "와이에오부카케"},
                {"ぁぃぅぇぉゃゅょゎ", "아이우에오야유요와"}};
        String[][] pairs = {
                {"きゃ", "캬"}, {"きゅ", "큐"}, {"きょ", "쿄"},
                {"ぎゃ", "갸"}, {"ぎゅ", "규"}, {"ぎょ", "교"},
                {"しゃ", "샤"}, {"しゅ", "슈"}, {"しょ", "쇼"}, {"しぇ", "셰"},
                {"じゃ", "자"}, {"じゅ", "주"}, {"じょ", "조"}, {"じぇ", "제"},
                {"ちゃ", "차"}, {"ちゅ", "추"}, {"ちょ", "초"}, {"ちぇ", "체"},
                {"ぢゃ", "자"}, {"ぢゅ", "주"}, {"ぢょ", "조"},
                {"にゃ", "냐"}, {"にゅ", "뉴"}, {"にょ", "뇨"},
                {"ひゃ", "햐"}, {"ひゅ", "휴"}, {"ひょ", "효"},
                {"びゃ", "뱌"}, {"びゅ", "뷰"}, {"びょ", "뵤"},
                {"ぴゃ", "퍄"}, {"ぴゅ", "퓨"}, {"ぴょ", "표"},
                {"みゃ", "먀"}, {"みゅ", "뮤"}, {"みょ", "묘"},
                {"りゃ", "랴"}, {"りゅ", "류"}, {"りょ", "료"},
                {"ふぁ", "파"}, {"ふぃ", "피"}, {"ふぇ", "페"}, {"ふぉ", "포"}, {"ふゅ", "퓨"},
                {"てぃ", "티"}, {"でぃ", "디"}, {"とぅ", "투"}, {"どぅ", "두"}, {"てゅ", "튜"}, {"でゅ", "듀"},
                {"うぃ", "위"}, {"うぇ", "웨"}, {"うぉ", "워"},
                {"ゔぁ", "바"}, {"ゔぃ", "비"}, {"ゔぇ", "베"}, {"ゔぉ", "보"},
                {"つぁ", "차"}, {"つぃ", "치"}, {"つぇ", "체"}, {"つぉ", "초"},
                {"いぇ", "예"}, {"くぁ", "콰"}, {"ぐぁ", "과"}, {"すぃ", "시"}, {"ずぃ", "지"}};
        Map<String, String> table = new HashMap<>();
        for (String[] row : rows) {
            for (int i = 0; i < row[0].length(); i++) table.put(String.valueOf(row[0].charAt(i)), String.valueOf(row[1].charAt(i)));
        }
        for (String[] pair : pairs) table.put(pair[0], pair[1]);
        return Map.copyOf(table);
    }
}
