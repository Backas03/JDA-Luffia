package kr.kro.backas.music.ai;

import com.sedmelluq.discord.lavaplayer.track.AudioTrackInfo;
import kr.kro.backas.util.DurationUtil;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

public final class TrackHints {

    public static final String SLANG_GUIDE = "Korean music slang: 보카로 = Vocaloid songs (voice synthesizer songs such as 初音ミク songs by producers like ハチ, DECO*27, wowaka), 애니송 = anime theme songs, 시티팝 = city pop, 제이팝 = J-pop, 케이팝 = K-pop, 발라드 = ballad, 인디 = indie.";

    public static final String HINT_GUIDE = String.join("\n",
            PromptSafe.DATA_RULE,
            SLANG_GUIDE,
            "Use what you know about the artists and songs, together with the hints on each line:",
            "- script: writing systems in the title and artist. kana = Japanese, hangul = Korean, han = Chinese characters (Chinese or Japanese kanji), latin = English or romanized.",
            "- isrc: country where the recording was registered (JP = Japan, KR = Korea, US = United States, ...). - means unknown."
                    + " It is only a weak hint: K-pop groups often register recordings in Japan or the US. What you know about the artist comes first.",
            "- A song belongs to the music scene of its artist, and to one scene only: K-pop groups are K-pop and Korean songs even when their members are Japanese,"
                    + " when they sing in English or Japanese, or when the isrc is JP (XG, TWICE, BTS, NewJeans). Japanese songs and J-pop are songs by artists of the Japanese scene."
                    + " Western artists are pop, never J-pop or K-pop.",
            "- A title in Latin letters does not mean English vocals. Japanese and Korean artists often use English or romanized titles and still sing in their own language.",
            "- Genre hints often appear in titles or artist names, e.g. 初音ミク, 鏡音リン, 巡音ルカ, GUMI, IA, 可不, 重音テト or VOCALOID for vocaloid songs.");

    private static final int MAX_FIELD_LENGTH = 120;

    private TrackHints() {
    }

    public static String describe(AudioTrackInfo info) {
        return "title: " + field(info.title)
                + " | artist: " + field(info.author)
                + " | script: " + script(info.title + " " + info.author)
                + " | isrc: " + isrcCountry(info.isrc)
                + " | length: " + (info.isStream ? "live" : DurationUtil.formatDurationColon((int) (info.length / 1000)));
    }

    public static String describe(AudioTrackInfo info, @Nullable AiTrackTagger.Tag tag) {
        if (tag == null) return describe(info);
        return describe(info) + " | tag: " + tag.genre() + ", " + tag.language();
    }

    public static String field(@Nullable String value) {
        if (value == null || value.isBlank()) return "-";
        String cleaned = PromptSafe.data(value).replaceAll("\\s+", " ").strip();
        return cleaned.length() > MAX_FIELD_LENGTH ? cleaned.substring(0, MAX_FIELD_LENGTH) : cleaned;
    }

    public static String script(String text) {
        int hangul = 0;
        int kana = 0;
        int han = 0;
        int latin = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if ((c >= 0xAC00 && c <= 0xD7A3) || (c >= 0x1100 && c <= 0x11FF) || (c >= 0x3130 && c <= 0x318F)) hangul++;
            else if (c >= 0x3040 && c <= 0x30FF) kana++;
            else if (c >= 0x4E00 && c <= 0x9FFF) han++;
            else if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z')) latin++;
        }
        List<String> present = new ArrayList<>();
        if (kana > 0) present.add("kana");
        if (hangul > 0) present.add("hangul");
        if (han > 0) present.add("han");
        if (latin > 0) present.add("latin");
        return present.isEmpty() ? "other" : String.join("+", present);
    }

    public static String isrcCountry(@Nullable String isrc) {
        if (isrc == null || isrc.length() < 2) return "-";
        return isrc.substring(0, 2).toUpperCase();
    }
}
