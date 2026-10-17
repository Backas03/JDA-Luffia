package kr.kro.backas.music.lyrics.sync;

import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import com.sedmelluq.discord.lavaplayer.track.AudioTrackInfo;
import kr.kro.backas.Luffia;
import kr.kro.backas.Main;
import kr.kro.backas.config.Config;
import kr.kro.backas.music.MusicPlayerClient;
import kr.kro.backas.music.ai.AiTrackTagger;
import kr.kro.backas.music.cache.DiskCache;
import kr.kro.backas.music.lyrics.LyricLine;
import kr.kro.backas.music.lyrics.Lyrics;
import kr.kro.backas.music.lyrics.TranslationJobs;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

public final class LyricsTranscriber {

    public static final String NOTE = "AI로 생성된 가사입니다. 정확하지 않을 수 있습니다.";
    public record Stored(@Nullable List<AiLyricsStore.Line> lines, long at, int version) {
    }

    private record Sung(long onsetMs, long endMs) {
    }

    static final int VERSION = 4;
    static final long REST_GAP_MS = 3_000;
    static final long REST_DELAY_MS = 500;
    static final long SNAP_MS = 30;
    static final long CJK_CHAR_MS = 200;
    static final long LATIN_CHAR_MS = 110;
    static final long MIN_SUNG_MS = 250;
    static final long SMEAR_RATIO = 5;
    static final long SMEAR_MS = 3_000;
    private static final Pattern BRACKETED = Pattern.compile("^[\\[(（【♪].*[\\])）】♪]$");
    static final int MAX_PROMPT_LENGTH = 100;
    static final long LANGUAGE_GUESS_TIMEOUT_MS = 30_000;
    private static final Set<String> HINTABLE_LANGUAGES = Set.of("ko", "ja", "en", "zh");
    private static final Pattern PROMPT_BRACKETS = Pattern.compile("\\([^)]*\\)|\\[[^\\]]*\\]|【[^】]*】|（[^）]*）|［[^］]*］|〔[^〕]*〕");
    private static final Pattern PROMPT_NOISE = Pattern.compile("(?iu)\\b(official|music\\s*video|lyric\\s*video|lyrics?|visualizer|audio|mv|m/v|hd|4k|full\\s*ver\\.?|ver\\.)\\b"
            + "|ミュージックビデオ|歌詞付き|フルver|公式|뮤직비디오|가사");
    private static final Pattern PROMPT_AUTHOR_NOISE = Pattern.compile("(?iu)\\s*-\\s*topic$|vevo$|\\s+official(\\s+channel)?$");
    private static final Pattern PROMPT_EDGES = Pattern.compile("^[\\s\\-–—|｜/:：・]+|[\\s\\-–—|｜/:：・]+$");
    private static final List<String> HALLUCINATIONS = List.of(
            "ご視聴ありがとう", "ご清聴ありがとう", "チャンネル登録", "高評価", "作詞", "作曲", "編曲", "字幕",
            "시청해 주셔서", "구독", "좋아요와", "자막",
            "thank you for watching", "thanks for watching", "subscribe", "subtitles by", "transcribed by");
    private static final Logger LOGGER = LoggerFactory.getLogger(LyricsTranscriber.class);
    private static final Map<String, CompletableFuture<Optional<Lyrics>>> IN_FLIGHT = new ConcurrentHashMap<>();

    private LyricsTranscriber() {
    }

    public static boolean isEnabled() {
        return Config.get().whisper().transcribe().enabled() && LyricsConverter.isAvailable();
    }

    @Nullable
    public static Lyrics cached(AudioTrack track) {
        Stored stored = DiskCache.defaultCache().read(path(track), Stored.class);
        if (stored == null || stored.version() != VERSION || stored.lines() == null || stored.lines().isEmpty()) return null;
        List<LyricLine> lines = new ArrayList<>();
        for (AiLyricsStore.Line line : stored.lines()) lines.add(new LyricLine(line.timeMs(), line.text()));
        return lyrics(track.getInfo(), lines);
    }

    public static CompletableFuture<Optional<Lyrics>> transcribe(MusicPlayerClient client, AudioTrack track) {
        Stored stored = DiskCache.defaultCache().read(path(track), Stored.class);
        if (stored != null && stored.version() == VERSION) return CompletableFuture.completedFuture(Optional.ofNullable(cached(track)));
        WhisperClient whisper = whisper();
        if (whisper == null || !whisper.isAvailable()) return CompletableFuture.completedFuture(Optional.empty());
        String identifier = track.getIdentifier();
        CompletableFuture<Optional<Lyrics>> job = IN_FLIGHT.computeIfAbsent(identifier, key -> start(client, whisper, track));
        job.whenComplete((result, error) -> IN_FLIGHT.remove(identifier, job));
        return job;
    }

    private static CompletableFuture<Optional<Lyrics>> start(MusicPlayerClient client, WhisperClient whisper, AudioTrack track) {
        AudioTrackInfo info = track.getInfo();
        long length = info.length;
        int seconds = (int) (length <= 0 ? LyricsConverter.MAX_SECONDS : Math.min(LyricsConverter.MAX_SECONDS, length / 1000 + 2));
        long started = System.currentTimeMillis();
        CompletableFuture<String> spoken = CompletableFuture.supplyAsync(() -> spokenLanguage(track), TranslationJobs.EXECUTOR)
                .completeOnTimeout(null, LANGUAGE_GUESS_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .exceptionally(error -> null);
        String prompt = prompt(info);
        CompletableFuture<Optional<Lyrics>> job = ScratchCapture.capture(client.getAudioPlayerManager(), track, seconds)
                .thenApplyAsync(captured -> {
                    if (captured == null) {
                        LOGGER.info("not enough audio captured to transcribe lyrics for {}", info.title);
                        return Optional.<Lyrics>empty();
                    }
                    String language = languageHint(info, spoken.join());
                    WhisperClient.Transcript transcript;
                    try {
                        transcript = whisper.transcribeDetailed(captured.wav(), language, prompt);
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                    List<LyricLine> lines = LyricsTimestamper.placeRests(lines(transcript, captured.capturedMs()), VocalActivity.of(captured.wav()));
                    boolean usable = letters(lines) >= Config.get().whisper().transcribe().minLetters();
                    List<AiLyricsStore.Line> stored = new ArrayList<>();
                    for (LyricLine line : lines) stored.add(new AiLyricsStore.Line(line.timeMs(), line.text()));
                    DiskCache.defaultCache().write(path(track), new Stored(usable ? stored : null, System.currentTimeMillis(), VERSION));
                    LOGGER.info("transcribed lyrics for {} ({}, prompt {}): {} line(s), {} letters, {} ({}ms)", info.title,
                            language == null ? "auto" : language, prompt == null ? "none" : "'" + prompt + "'", lines.size(), letters(lines),
                            usable ? "shown" : "too little to show", System.currentTimeMillis() - started);
                    return usable ? Optional.of(lyrics(info, lines)) : Optional.<Lyrics>empty();
                }, TranslationJobs.EXECUTOR);
        job.whenComplete((result, error) -> {
            if (error != null) LOGGER.info("lyrics transcription failed for {}: {}", info.title, error.toString());
        });
        return job;
    }

    static List<LyricLine> lines(WhisperClient.Transcript transcript, long audioMs) {
        List<LyricLine> lines = new ArrayList<>();
        long lastEnd = -1;
        for (WhisperClient.Segment segment : transcript.segments()) {
            String text = segment.text().strip();
            if (!isLyric(text)) continue;
            List<WhisperClient.Word> words = sungWords(transcript.words(), segment);
            Sung sung = words.isEmpty() ? new Sung(segment.startMs(), segment.endMs()) : sung(segment, words);
            if (lastEnd < 0 && sung.onsetMs() > REST_GAP_MS) add(lines, 0, "");
            if (lastEnd >= 0 && sung.onsetMs() - lastEnd > REST_GAP_MS) add(lines, lastEnd + REST_DELAY_MS, "");
            add(lines, Math.max(0, sung.onsetMs() - LyricsTimestamper.LINE_LEAD_MS), text);
            lastEnd = Math.max(lastEnd, sung.endMs());
        }
        if (!lines.isEmpty() && lastEnd + REST_GAP_MS < audioMs) add(lines, lastEnd + REST_DELAY_MS, "");
        return lines;
    }

    private static List<WhisperClient.Word> sungWords(List<WhisperClient.Word> words, WhisperClient.Segment segment) {
        List<WhisperClient.Word> sung = new ArrayList<>();
        for (WhisperClient.Word word : words) {
            if (word.startMs() < segment.startMs() || word.startMs() >= segment.endMs()) continue;
            if (!LyricsAligner.normalize(word.text()).isEmpty()) sung.add(word);
        }
        return sung;
    }

    private static Sung sung(WhisperClient.Segment segment, List<WhisperClient.Word> words) {
        int head = 0;
        while (head < words.size() && smeared(words.get(head))) head++;
        int tail = words.size();
        while (tail > head && smeared(words.get(tail - 1))) tail--;
        WhisperClient.Word last = words.getLast();
        if (head == words.size() && last.endMs() - words.getFirst().startMs() >= SMEAR_MS) {
            return new Sung(last.endMs() - sungMs(words, 0, words.size()), last.endMs());
        }
        long onset = head > 0 && words.get(head - 1).endMs() - words.getFirst().startMs() >= SMEAR_MS
                ? words.get(head).startMs() - sungMs(words, 0, head)
                : onsetMs(segment, words);
        long end = tail < words.size() && last.endMs() - words.get(tail).startMs() >= SMEAR_MS
                ? words.get(tail).startMs() + sungMs(words, tail, words.size())
                : last.endMs();
        return new Sung(onset, Math.max(onset, end));
    }

    private static boolean smeared(WhisperClient.Word word) {
        return word.endMs() - word.startMs() >= SMEAR_RATIO * sungMs(word.text());
    }

    private static long sungMs(List<WhisperClient.Word> words, int from, int to) {
        long total = 0;
        for (int i = from; i < to; i++) total += sungMs(words.get(i).text());
        return total;
    }

    private static long onsetMs(WhisperClient.Segment segment, List<WhisperClient.Word> words) {
        WhisperClient.Word first = words.getFirst();
        long duration = sungMs(first.text());
        boolean snapped = first.startMs() - segment.startMs() <= SNAP_MS;
        long onset = Math.max(first.startMs(), first.endMs() - (snapped ? 0 : duration));
        if (words.size() > 1) onset = Math.max(onset, Math.min(words.get(1).startMs() - duration, first.endMs() + MIN_SUNG_MS));
        return onset;
    }

    private static long sungMs(String text) {
        long total = 0;
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            i += Character.charCount(cp);
            if (!Character.isLetterOrDigit(cp)) continue;
            total += cp < 0x3000 ? LATIN_CHAR_MS : CJK_CHAR_MS;
        }
        return Math.max(MIN_SUNG_MS, total);
    }

    private static void add(List<LyricLine> lines, long at, String text) {
        if (!lines.isEmpty() && at <= lines.getLast().timeMs()) at = lines.getLast().timeMs() + 1;
        lines.add(new LyricLine(at, text));
    }

    static boolean isLyric(String text) {
        if (LyricsAligner.normalize(text).isEmpty() || BRACKETED.matcher(text).matches()) return false;
        String lower = text.toLowerCase(Locale.ROOT);
        for (String phrase : HALLUCINATIONS) {
            if (lower.contains(phrase)) return false;
        }
        return true;
    }

    static int letters(List<LyricLine> lines) {
        int count = 0;
        for (LyricLine line : lines) count += LyricsAligner.normalize(line.text()).length();
        return count;
    }

    @Nullable
    static String languageHint(AudioTrackInfo info, @Nullable String spoken) {
        String text = info.title + " " + info.author;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c >= 0x3040 && c <= 0x30FF) return "ja";
        }
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c >= 0xAC00 && c <= 0xD7A3) return "ko";
        }
        return spoken != null && HINTABLE_LANGUAGES.contains(spoken) ? spoken : null;
    }

    @Nullable
    private static String spokenLanguage(AudioTrack track) {
        Luffia luffia = Main.getLuffia();
        if (luffia == null || !luffia.getMusicPlayerController().getTranslationClient().isAvailable()) return null;
        AiTrackTagger tagger = luffia.getMusicPlayerController().getAiTrackTagger();
        try {
            AiTrackTagger.Tag tag = tagger.tag(List.of(track), done -> {
            }).get(track);
            return tag == null ? null : tag.language();
        } catch (IOException | RuntimeException e) {
            LOGGER.info("could not guess the vocal language of {}: {}", track.getInfo().title, e.toString());
            return null;
        }
    }

    @Nullable
    static String prompt(AudioTrackInfo info) {
        String title = cleanPrompt(info.title, PROMPT_NOISE);
        String author = cleanPrompt(info.author, PROMPT_AUTHOR_NOISE);
        String line;
        if (title.isEmpty()) line = author;
        else if (author.isEmpty() || title.toLowerCase(Locale.ROOT).contains(author.toLowerCase(Locale.ROOT))) line = title;
        else line = author + " - " + title;
        if (line.isEmpty()) return null;
        if (line.codePointCount(0, line.length()) > MAX_PROMPT_LENGTH) {
            line = line.substring(0, line.offsetByCodePoints(0, MAX_PROMPT_LENGTH)).strip();
        }
        return line;
    }

    private static String cleanPrompt(@Nullable String text, Pattern noise) {
        if (text == null) return "";
        String cleaned = PROMPT_BRACKETS.matcher(text).replaceAll(" ");
        cleaned = noise.matcher(cleaned).replaceAll(" ");
        cleaned = cleaned.replaceAll("[\\s\\u3000]+", " ").strip();
        return PROMPT_EDGES.matcher(cleaned).replaceAll("").strip();
    }

    private static Lyrics lyrics(AudioTrackInfo info, List<LyricLine> lines) {
        StringBuilder plain = new StringBuilder();
        for (LyricLine line : lines) {
            if (!line.text().isBlank()) plain.append(line.text()).append('\n');
        }
        return new Lyrics(info.title, info.author, plain.toString().strip(), lines, false, false, true, Lyrics.Source.AI);
    }

    private static String path(AudioTrack track) {
        return "ai-transcripts/" + DiskCache.safeName(track.getIdentifier()) + ".json";
    }

    @Nullable
    private static WhisperClient whisper() {
        Luffia luffia = Main.getLuffia();
        return luffia == null ? null : luffia.getMusicPlayerController().getWhisperClient();
    }
}
