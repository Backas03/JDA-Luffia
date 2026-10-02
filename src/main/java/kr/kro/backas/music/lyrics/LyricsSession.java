package kr.kro.backas.music.lyrics;

import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import kr.kro.backas.music.MusicEmbeds;
import kr.kro.backas.music.MusicPlayerClient;
import kr.kro.backas.util.DiscordSafe;
import kr.kro.backas.util.DurationUtil;
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
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public class LyricsSession {
    private static final int MAX_LINE_LENGTH = 300;
    private static final int MAX_SONG_LENGTH = 200;

    private static final Logger LOGGER = LoggerFactory.getLogger(LyricsSession.class);
    public static final long TICK_MS = 200;
    public static final long MIN_EDIT_INTERVAL_MS = 1200;
    private static final long CLOCK_EDIT_INTERVAL_MS = 1000;
    private static final long CLOCK_EDIT_GUARD_MS = 300;
    private static final int CLOCK_EDIT_RESERVE = 1;
    public static final long DEFAULT_OFFSET_MS = 600;
    public static final String TRANSLATING_NOTE = "번역 중...";
    private static final String BLANK = "​";
    private static final String WIDTH_FILLER = "⠀".repeat(48);
    private static final String CLOCK_SEPARATOR = " — ";
    public static final String REST = "♪";
    private static final String FAILED_TRANSLATION = "-";

    private final MusicPlayerClient client;
    private final AudioTrack track;
    private final Lyrics lyrics;
    private final Message message;
    private final ScheduledExecutorService scheduler;
    private final TranslationClient translator;
    private final Map<Integer, String> translations;
    private final List<String> sources;
    private final String cacheKey;
    private final boolean translatable;
    private volatile long offsetMs;
    private volatile boolean translating;
    private volatile TranslationJobs.Job job;
    private ScheduledFuture<?> ticker;
    private int shownIndex = -2;
    private String shownTranslation;
    private boolean shownPending;
    private String shownClock = "";
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
        this.sources = new ArrayList<>(lyrics.synced().size());
        for (LyricLine line : lyrics.synced()) this.sources.add(line.text());
        this.cacheKey = TranslationJobs.cacheKey("synced", sources);
        this.translations = this.translator == null ? Map.of() : this.translator.cacheFor(cacheKey);
        this.translatable = isTranslatable(this.translator, lyrics);
        this.offsetMs = offsetMs;
    }

    public void start() {
        ticker = scheduler.scheduleAtFixedRate(this::tick, 0, TICK_MS, TimeUnit.MILLISECONDS);
        startTranslation();
        LyricsPresenter.prefetchNext(client);
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
        if (!halt()) return;
        try {
            message.editMessageComponents(buildView(client, track, lyrics, shownIndex, translationFor(shownIndex), reason, translator, false))
                    .useComponentsV2(true)
                    .queue(null, e -> {});
        } catch (RuntimeException e) {
            LOGGER.debug("failed to finalize lyrics message", e);
        }
    }

    public void dismiss() {
        if (!halt()) return;
        deleteMessage(message);
    }

    static void deleteMessage(Message message) {
        try {
            message.delete().queue(null, e -> LOGGER.debug("failed to delete lyrics message", e));
        } catch (RuntimeException e) {
            LOGGER.debug("failed to delete lyrics message", e);
        }
    }

    private boolean halt() {
        if (!stopped.compareAndSet(false, true)) return false;
        if (ticker != null) ticker.cancel(false);
        TranslationJobs.Job current = job;
        if (current != null) current.cancel();
        return true;
    }

    private boolean startTranslation() {
        if (translator == null) return false;
        if (!translatable) {
            LyricsPresenter.reportSongComplete(client, track);
            return false;
        }
        boolean pending = false;
        for (int i = 0; i < sources.size(); i++) {
            if (!translations.containsKey(i) && LyricsLanguage.needsTranslation(sources.get(i))) pending = true;
        }
        if (!pending) {
            LyricsPresenter.reportSongComplete(client, track);
            return false;
        }
        translating = true;
        job = TranslationJobs.submit(translator, cacheKey, sources, true, null, TranslationJobs.songContext(track));
        LyricsPresenter.reportSongJob(client, track, job);
        job.done().whenComplete((result, error) -> {
            translating = false;
            LyricsPresenter.prefetchNext(client);
        });
        return true;
    }

    public static boolean isTranslatable(@Nullable TranslationClient translator, Lyrics lyrics) {
        return translator != null && translator.isEnabled()
                && !LyricsLanguage.KOREAN.equals(LyricsLanguage.detect(lyrics.synced()));
    }

    @Nullable
    private String translationFor(int index) {
        if (!translatable) return null;
        String source = index < 0 || index >= sources.size() || sources.get(index) == null ? "" : sources.get(index);
        if (source.isBlank()) return REST;
        String cached = translations.get(index);
        if (cached != null && !cached.isBlank()) return cached;
        if (!LyricsLanguage.needsTranslation(source)) return source;
        return cached == null && translating ? null : FAILED_TRANSLATION;
    }

    private boolean isPendingTranslation(int index, @Nullable String translation) {
        if (translation != null || !translating || index < 0 || translations.containsKey(index)) return false;
        String text = lineText(lyrics.synced(), index);
        return LyricsLanguage.needsTranslation(text);
    }

    private void tick() {
        try {
            if (stopped.get()) return;
            if (!isForTrack(client.getCurrentPlaying())) {
                dismiss();
                return;
            }
            if (client.isPaused()) return;
            long position = (long) (client.getRealPositionMs() + offsetMs * client.getCurrentPlaySpeed());
            int index = indexAt(position);
            String translation = translationFor(index);
            boolean pending = isPendingTranslation(index, translation);
            boolean sameLine = index == shownIndex;
            boolean sameTranslation = translation == null ? shownTranslation == null : translation.equals(shownTranslation);
            boolean clockOnly = sameLine && sameTranslation && pending == shownPending;
            String clock = playbackClock(client, track);
            if (clockOnly && (clock.equals(shownClock) || !hasRoomBeforeNextLine(index, position))) return;
            long now = System.currentTimeMillis();
            if (now - lastEditAt < (clockOnly ? CLOCK_EDIT_INTERVAL_MS : MIN_EDIT_INTERVAL_MS)) return;
            if (!editInFlight.compareAndSet(false, true)) return;
            if (!EditRateLimiter.tryAcquire(message.getChannel().getIdLong(), clockOnly ? CLOCK_EDIT_RESERVE : 0)) {
                editInFlight.set(false);
                return;
            }
            shownIndex = index;
            shownTranslation = translation;
            shownPending = pending;
            shownClock = clock;
            lastEditAt = now;
            message.editMessageComponents(buildView(client, track, lyrics, index, translation, null, translator, pending))
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

    private boolean hasRoomBeforeNextLine(int index, long positionMs) {
        List<LyricLine> lines = lyrics.synced();
        if (index + 1 >= lines.size()) return true;
        double speed = Math.max(0.1, client.getCurrentPlaySpeed());
        return (lines.get(index + 1).timeMs() - positionMs) / speed > MIN_EDIT_INTERVAL_MS + CLOCK_EDIT_GUARD_MS;
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
        return buildView(null, track, lyrics, index, translation, footer, null, false);
    }

    public static Container buildView(@Nullable MusicPlayerClient client, AudioTrack track, Lyrics lyrics, int index,
                                      @Nullable String translation,
                                      @Nullable String footer, @Nullable TranslationClient translator, boolean pendingTranslation) {
        List<LyricLine> lines = lyrics.synced();
        String previous = lineText(lines, index - 1);
        String current = lineText(lines, index);
        String next = lineText(lines, index + 1);
        String song = DiscordSafe.text(track.getInfo().author + " - " + track.getInfo().title, MAX_SONG_LENGTH);
        translation = translation == null ? null : DiscordSafe.text(translation, MAX_LINE_LENGTH);
        StringBuilder text = new StringBuilder();
        text.append(previous.isBlank() ? BLANK : "*" + previous + "*").append('\n');
        text.append("## ").append(current.isBlank() ? REST : current).append('\n');
        if (translation != null && !translation.isBlank()) {
            text.append("-# ").append(translation).append('\n').append(BLANK).append('\n');
        } else if (pendingTranslation) {
            text.append("-# ").append(TRANSLATING_NOTE).append('\n').append(BLANK).append('\n');
        }
        text.append(next.isBlank() ? BLANK : "*" + next + "*").append('\n');
        text.append("-# ").append(WIDTH_FILLER).append('\n');
        text.append("-# ").append(song);
        if (footer != null) text.append(" · ").append(footer);
        else if (client != null) text.append(" | ").append(playbackClock(client, track));
        if ((translation != null && !translation.isBlank()) || pendingTranslation) {
            String note = LyricsPresenter.translationStatus(client, translator);
            if (!note.isBlank()) {
                for (String noteLine : note.split("\n")) {
                    text.append('\n');
                    if (!noteLine.isBlank()) text.append("-# ").append(noteLine);
                }
            }
        }
        TextDisplay body = TextDisplay.of(text.toString());
        String artwork = MusicEmbeds.thumbnailOf(track);
        Container container = artwork == null
                ? Container.of(body)
                : Container.of(Section.of(Thumbnail.fromUrl(artwork), body));
        return container.withAccentColor(MusicEmbeds.PRIMARY);
    }

    static String playbackClock(MusicPlayerClient client, AudioTrack track) {
        long length = track.getInfo().length;
        long position = Math.max(0, (long) client.getRealPositionMs());
        if (track.getInfo().isStream || length <= 0) return DurationUtil.formatClock(position / 1000);
        return DurationUtil.formatClock(Math.min(position, length) / 1000) + CLOCK_SEPARATOR + DurationUtil.formatClock(length / 1000);
    }

    private static String lineText(List<LyricLine> lines, int index) {
        if (index < 0 || index >= lines.size()) return "";
        return DiscordSafe.text(lines.get(index).text(), MAX_LINE_LENGTH);
    }
}
