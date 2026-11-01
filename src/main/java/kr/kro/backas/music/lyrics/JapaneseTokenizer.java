package kr.kro.backas.music.lyrics;

import com.atilika.kuromoji.ipadic.Tokenizer;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

public final class JapaneseTokenizer {

    private static final String READING_FIXES = String.join("\n",
            "一人,一人,ヒトリ,名詞",
            "二人,二人,フタリ,名詞",
            "愛して,愛して,アイシテ,動詞",
            "愛してる,愛してる,アイシテル,動詞",
            "帰さ,帰さ,カエサ,動詞");
    private static volatile Tokenizer tokenizer;

    private JapaneseTokenizer() {
    }

    public static Tokenizer get() {
        Tokenizer current = tokenizer;
        if (current == null) {
            synchronized (JapaneseTokenizer.class) {
                current = tokenizer;
                if (current == null) {
                    current = build();
                    tokenizer = current;
                }
            }
        }
        return current;
    }

    private static Tokenizer build() {
        try {
            return new Tokenizer.Builder()
                    .userDictionary(new ByteArrayInputStream(READING_FIXES.getBytes(StandardCharsets.UTF_8)))
                    .build();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
