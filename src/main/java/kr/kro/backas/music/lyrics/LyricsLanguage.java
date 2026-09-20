package kr.kro.backas.music.lyrics;

import java.util.List;

public final class LyricsLanguage {

    public static final String KOREAN = "kor_Hang";
    public static final String JAPANESE = "jpn_Jpan";
    public static final String CHINESE = "zho_Hans";
    public static final String ENGLISH = "eng_Latn";
    public static final String RUSSIAN = "rus_Cyrl";

    private LyricsLanguage() {
    }

    public static String detect(List<LyricLine> lines) {
        int hangul = 0;
        int kana = 0;
        int han = 0;
        int latin = 0;
        int cyrillic = 0;
        for (LyricLine line : lines) {
            String text = line.text();
            if (text == null) continue;
            for (int i = 0; i < text.length(); i++) {
                char c = text.charAt(i);
                if ((c >= 0xAC00 && c <= 0xD7A3) || (c >= 0x1100 && c <= 0x11FF) || (c >= 0x3130 && c <= 0x318F)) hangul++;
                else if (c >= 0x3040 && c <= 0x30FF) kana++;
                else if (c >= 0x4E00 && c <= 0x9FFF) han++;
                else if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z')) latin++;
                else if (c >= 0x0400 && c <= 0x04FF) cyrillic++;
            }
        }
        if (kana > 0 && kana * 4 >= han) {
            kana += han;
            han = 0;
        }
        int best = Math.max(Math.max(hangul, kana), Math.max(Math.max(han, latin), cyrillic));
        if (best == 0) return ENGLISH;
        if (best == hangul) return KOREAN;
        if (best == kana) return JAPANESE;
        if (best == han) return CHINESE;
        if (best == cyrillic) return RUSSIAN;
        return ENGLISH;
    }

    public static boolean needsTranslation(String text) {
        if (text == null || text.isBlank()) return false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if ((c >= 0x3040 && c <= 0x30FF) || (c >= 0x4E00 && c <= 0x9FFF)
                    || (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= 0x0400 && c <= 0x04FF)) {
                return true;
            }
        }
        return false;
    }
}
