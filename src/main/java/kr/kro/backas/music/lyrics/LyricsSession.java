package kr.kro.backas.music.lyrics;

import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import kr.kro.backas.music.MusicEmbeds;
import kr.kro.backas.music.MusicPlayerClient;
import net.dv8tion.jda.api.components.container.Container;
import net.dv8tion.jda.api.components.section.Section;
import net.dv8tion.jda.api.components.textdisplay.TextDisplay;
import net.dv8tion.jda.api.components.thumbnail.Thumbnail;
import net.dv8tion.jda.api.entities.Message;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public class LyricsSession {

    private static final Logger LOGGER = LoggerFactory.getLogger(LyricsSession.class);
    public static final long TICK_MS = 200;
    public static final long MIN_EDIT_INTERVAL_MS = 1200;
    public static final long DEFAULT_OFFSET_MS = 600;
    public static final int FIRST_TRANSLATION_CHUNK = 5;
    public static final int TRANSLATION_CHUNK = 10;
    public static final String TRANSLATING_NOTE = "번역 중...";
    private static final String BLANK = "​";
    private static final String WIDTH_FILLER = "⠀".repeat(48);

    private final MusicPlayerClient client;
    private final AudioTrack track;
    private final Lyrics lyrics;
    private final Message message;
    private final ScheduledExecutorService scheduler;
    private final TranslationClient translator;
    private final Map<Integer, String> translations;
    private volatile long offsetMs;
    private volatile boolean translating;
    private ScheduledFuture<?> ticker;
    private int shownIndex = -2;
    private String shownTranslation;
    private boolean shownPending;
    private long lastEditAt;
    private final AtomicBoolean editInFlight = new AtomicBoolean(false);
    private final AtomicBoolean stopped = new AtomicBoolean(false);

    public LyricsSession(MusicPlayerClient client,
                         AudioTrack track,
                         Lyrics lyrics,
                         Message message,
                         ScheduledExecutorService scheduler,
                         @Nullable TranslationClient translator,
                         long offsetMs) {
        this.client = client;
        this.track = track;
        this.lyrics = lyrics;
        this.message = message;
        this.scheduler = scheduler;
        this.translator = translator == null || !translator.isEnabled() ? null : translator;
        this.translations = this.translator == null ? Map.of() : this.translator.cacheFor(track.getIdentifier());
        this.offsetMs = offsetMs;
    }

    public void start() {
        ticker = scheduler.scheduleAtFixedRate(this::tick, 0, TICK_MS, TimeUnit.MILLISECONDS);
        startTranslation();
    }

    public void setOffsetMs(long offsetMs) {
        this.offsetMs = offsetMs;
    }

    public long getOffsetMs() {
        return offsetMs;
    }

    public AudioTrack getTrack() {
        return track;
    }

    public boolean isForTrack(AudioTrack other) {
        return other != null && other.getIdentifier().equals(track.getIdentifier());
    }

    public void stop(String reason) {
        if (!stopped.compareAndSet(false, true)) return;
        if (ticker != null) ticker.cancel(false);
        try {
            message.editMessageComponents(buildView(track, lyrics, shownIndex, translationFor(shownIndex), reason, translator, false))
                    .useComponentsV2(true)
                    .queue(null, e -> {});
        } catch (RuntimeException e) {
            LOGGER.debug("failed to finalize lyrics message", e);
        }
    }

    private void startTranslation() {
        if (translator == null) return;
        List<LyricLine> lines = lyrics.synced();
        if (LyricsLanguage.KOREAN.equals(LyricsLanguage.detect(lines))) return;
        List<Integer> pending = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            if (!translations.containsKey(i) && LyricsLanguage.needsTranslation(lines.get(i).text())) pending.add(i);
        }
        if (pending.isEmpty()) return;
        translating = true;
        CompletableFuture.runAsync(() -> {
            try {
                int from = 0;
                int chunk = FIRST_TRANSLATION_CHUNK;
                while (from < pending.size() && !stopped.get()) {
                    int to = Math.min(pending.size(), from + chunk);
                    translateChunk(lines, pending.subList(from, to));
                    from = to;
                    chunk = TRANSLATION_CHUNK;
                }
            } finally {
                translating = false;
            }
        });
    }

    private void translateChunk(List<LyricLine> lines, List<Integer> indices) {
        List<String> sources = new ArrayList<>(indices.size());
        for (int index : indices) sources.add(lines.get(index).text());
        try {
            List<String> translated = translator.translate("auto", sources);
            for (int i = 0; i < indices.size(); i++) {
                String text = translated.get(i);
                if (text != null && !text.isBlank() && !text.equals(sources.get(i))) {
                    translations.put(indices.get(i), text);
                }
            }
        } catch (Exception e) {
            LOGGER.warn("lyrics translation failed for {} ({} lines)", track.getInfo().title, indices.size(), e);
        }
    }

    @Nullable
    private String translationFor(int index) {
        return index < 0 ? null : translations.get(index);
    }

    private boolean isPendingTranslation(int index, @Nullable String translation) {
        if (translation != null || !translating || index < 0) return false;
        String text = lineText(lyrics.synced(), index);
        return LyricsLanguage.needsTranslation(text);
    }

    private void tick() {
        try {
            if (stopped.get()) return;
            if (!isForTrack(client.getCurrentPlaying())) {
                stop("재생이 끝났습니다");
                return;
            }
            if (client.isPaused()) return;
            long position = (long) client.getRealPositionMs() + offsetMs;
            int index = indexAt(position);
            String translation = translationFor(index);
            boolean pending = isPendingTranslation(index, translation);
            boolean sameLine = index == shownIndex;
            boolean sameTranslation = translation == null ? shownTranslation == null : translation.equals(shownTranslation);
            if (sameLine && sameTranslation && pending == shownPending) return;
            long now = System.currentTimeMillis();
            if (now - lastEditAt < MIN_EDIT_INTERVAL_MS) return;
            if (!editInFlight.compareAndSet(false, true)) return;
            shownIndex = index;
            shownTranslation = translation;
            shownPending = pending;
            lastEditAt = now;
            message.editMessageComponents(buildView(track, lyrics, index, translation, null, translator, pending))
                    .useComponentsV2(true)
                    .queue(
                            m -> editInFlight.set(false),
                            e -> {
                                editInFlight.set(false);
                                LOGGER.debug("lyrics edit failed", e);
                            });
        } catch (RuntimeException e) {
            LOGGER.warn("lyrics tick failed", e);
            stop("가사 표시 중 오류가 발생했습니다");
        }
    }

    private int indexAt(long positionMs) {
        List<LyricLine> lines = lyrics.synced();
        int index = -1;
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).timeMs() <= positionMs) index = i;
            else break;
        }
        return index;
    }

    public static Container buildView(AudioTrack track, Lyrics lyrics, int index, @Nullable String translation, @Nullable String footer) {
        return buildView(track, lyrics, index, translation, footer, null, false);
    }

    public static Container buildView(AudioTrack track, Lyrics lyrics, int index, @Nullable String translation,
                                      @Nullable String footer, @Nullable TranslationClient translator, boolean pendingTranslation) {
        List<LyricLine> lines = lyrics.synced();
        String previous = lineText(lines, index - 1);
        String current = lineText(lines, index);
        String next = lineText(lines, index + 1);
        String song = track.getInfo().author + " - " + track.getInfo().title;
        StringBuilder text = new StringBuilder();
        text.append(previous.isBlank() ? BLANK : "*" + previous + "*").append('\n');
        text.append("## ").append(current.isBlank() ? "♪" : current).append('\n');
        if (translation != null && !translation.isBlank()) {
            text.append("-# ").append(translation).append('\n');
        } else if (pendingTranslation) {
            text.append("-# ").append(TRANSLATING_NOTE).append('\n');
        }
        text.append(next.isBlank() ? BLANK : "*" + next + "*").append('\n');
        text.append("-# ").append(WIDTH_FILLER).append('\n');
        text.append("-# ").append(footer == null ? song : song + " · " + footer);
        if ((translation != null && !translation.isBlank()) || pendingTranslation) {
            text.append('\n').append("-# ").append(LyricsPresenter.machineTranslationNote(translator));
        }
        TextDisplay body = TextDisplay.of(text.toString());
        String artwork = MusicEmbeds.thumbnailOf(track);
        Container container = artwork == null
                ? Container.of(body)
                : Container.of(Section.of(Thumbnail.fromUrl(artwork), body));
        return container.withAccentColor(MusicEmbeds.PRIMARY);
    }

    private static String lineText(List<LyricLine> lines, int index) {
        if (index < 0 || index >= lines.size()) return "";
        String text = lines.get(index).text();
        return text == null ? "" : text;
    }
}
