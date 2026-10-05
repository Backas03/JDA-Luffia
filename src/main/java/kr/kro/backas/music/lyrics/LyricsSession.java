package kr.kro.backas.music.lyrics;

import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import kr.kro.backas.music.ArtworkColors;
import kr.kro.backas.music.MusicEmbeds;
import kr.kro.backas.music.MusicPlayerClient;
import kr.kro.backas.music.lyrics.sync.LyricsAutoSync;
import kr.kro.backas.music.TrackCard;
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
import java.util.concurrent.atomic.AtomicLong;

public class LyricsSession {
    private static final int MAX_LINE_LENGTH = 300;
    private static final int MAX_SONG_LENGTH = 200;

    private static final Logger LOGGER = LoggerFactory.getLogger(LyricsSession.class);
    public static final long TICK_MS = 200;
    public static final long MIN_EDIT_INTERVAL_MS = 1200;
    private static final long CLOCK_EDIT_INTERVAL_MS = 800;
    private static final long POST_EDIT_GAP_MS = 500;
    public static final long DEFAULT_OFFSET_MS = 0;
    public static final String AUTO_PENDING_NOTE = "AI 보정 중";
    public static final String TRANSLATING_NOTE = "번역 중...";
    private static final String BLANK = "​";
    private static final String WIDTH_FILLER = "⠀".repeat(120);
    private static final String CLOCK_SEPARATOR = " — ";
    public static final String REST = "♪";
    private static final String FAILED_TRANSLATION = "-";

    private final MusicPlayerClient client;
    private final AudioTrack track;
    private final Lyrics lyrics;
    private final LyricsSurface surface;
    private final ScheduledExecutorService scheduler;
    private final TranslationClient translator;
    private final Map<Integer, String> translations;
    private final List<String> sources;
    private final String cacheKey;
    private final boolean translatable;
    private final long leadMs;
    private volatile long offsetMs;
    private volatile long autoOffsetMs;
    private volatile boolean autoPending;
    private volatile boolean translating;
    private volatile TranslationJobs.Job job;
    private ScheduledFuture<?> ticker;
    private int shownIndex = -2;
    private String shownTranslation;
    private boolean shownPending;
    private String shownClock = "";
    private long lastEditAt;
    private long lastContentEditAt;
    private final AtomicLong editInFlightSince = new AtomicLong();
    private final AtomicBoolean stopped = new AtomicBoolean(false);

    public LyricsSession(MusicPlayerClient client,
                         AudioTrack track,
                         Lyrics lyrics,
                         LyricsSurface surface,
                         ScheduledExecutorService scheduler,
                         @Nullable TranslationClient translator,
                         long offsetMs) {
        this.client = client;
        this.track = track;
        this.lyrics = lyrics;
        this.surface = surface;
        this.scheduler = scheduler;
        this.translator = translator == null || !translator.isEnabled() ? null : translator;
        this.sources = new ArrayList<>(lyrics.synced().size());
        for (LyricLine line : lyrics.synced()) this.sources.add(line.text());
        this.cacheKey = TranslationJobs.cacheKey("synced", sources);
        this.translations = this.translator == null ? Map.of() : this.translator.cacheFor(cacheKey);
        this.translatable = isTranslatable(this.translator, lyrics);
        this.leadMs = EditLatency.leadMs(surface.channelId());
        this.offsetMs = offsetMs;
    }

    public void start() {
        ticker = scheduler.scheduleAtFixedRate(this::tick, 0, TICK_MS, TimeUnit.MILLISECONDS);
        startTranslation();
        LyricsPresenter.prefetchNext(client);
        LyricsAutoSync.apply(client, track, lyrics, this);
    }

    public void setAutoOffsetMs(long autoOffsetMs) {
        this.autoOffsetMs = autoOffsetMs;
    }

    public long getAutoOffsetMs() {
        return autoOffsetMs;
    }

    public void setAutoPending(boolean pending) {
        this.autoPending = pending;
    }

    @Nullable
    private String autoNote() {
        if (autoPending) return AUTO_PENDING_NOTE;
        return lyrics.aiTimed() ? LyricsConversions.LABEL : null;
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
            surface.edit(view(shownIndex, translationFor(shownIndex), reason, false),
                            System.currentTimeMillis() + EditRateLimiter.EDIT_DEADLINE_MS)
                    .whenComplete((result, error) -> {
                        if (error != null) LOGGER.debug("failed to finalize lyrics message", error);
                    });
        } catch (RuntimeException e) {
            LOGGER.debug("failed to finalize lyrics message", e);
        }
    }

    public void dismiss() {
        if (!halt()) return;
        surface.dismiss();
    }

    public void detach() {
        halt();
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
            String cached = translations.get(i);
            if ((cached == null || cached.isBlank()) && LyricsLanguage.needsTranslation(sources.get(i))) pending = true;
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

    private Container view(int index, @Nullable String translation, @Nullable String footer, boolean pending) {
        return surface.frame(body(client, track, lyrics, index, translation, footer, translator, pending, surface.showsSong(), autoNote()));
    }

    private void tick() {
        try {
            if (stopped.get()) return;
            if (!isForTrack(client.getCurrentPlaying())) {
                dismiss();
                return;
            }
            if (client.isPaused()) return;
            long channelId = surface.channelId();
            long position = (long) (client.getRealPositionMs() + (leadMs + offsetMs + autoOffsetMs) * client.getCurrentPlaySpeed());
            int index = indexAt(position);
            String translation = translationFor(index);
            boolean pending = isPendingTranslation(index, translation);
            boolean sameLine = index == shownIndex;
            boolean sameTranslation = translation == null ? shownTranslation == null : translation.equals(shownTranslation);
            boolean clockOnly = sameLine && sameTranslation && pending == shownPending;
            String clock = playbackClock(client, track);
            if (clockOnly && (clock.equals(shownClock) || !hasRoomBeforeNextLine(index, position))) return;
            long now = System.currentTimeMillis();
            if (clockOnly) {
                if (now - lastEditAt < CLOCK_EDIT_INTERVAL_MS || !EditRateLimiter.extrasAllowed(channelId)) return;
            } else if (now - lastContentEditAt < MIN_EDIT_INTERVAL_MS || now - lastEditAt < POST_EDIT_GAP_MS) {
                return;
            }
            long inFlight = editInFlightSince.get();
            if (inFlight != 0) {
                if (now - inFlight < EditRateLimiter.IN_FLIGHT_TIMEOUT_MS) return;
                EditRateLimiter.reportHeldBack(channelId, "no response to an edit");
            }
            boolean acquired = clockOnly
                    ? EditRateLimiter.tryAcquire(channelId, upcomingLineEdits(index, position, now))
                    : EditRateLimiter.tryAcquire(channelId);
            if (!acquired) return;
            editInFlightSince.set(now);
            shownIndex = index;
            shownTranslation = translation;
            shownPending = pending;
            shownClock = clock;
            lastEditAt = now;
            if (!clockOnly) lastContentEditAt = now;
            surface.edit(view(index, translation, null, pending), now + EditRateLimiter.EDIT_DEADLINE_MS)
                    .whenComplete((result, error) -> {
                        editInFlightSince.compareAndSet(now, 0);
                        if (error == null) {
                            EditLatency.record(channelId, System.currentTimeMillis() - now);
                            return;
                        }
                        if (TrackCard.isGone(error)) {
                            LOGGER.info("lyrics message for {} was deleted, stopping until the next track", track.getInfo().title);
                            halt();
                            return;
                        }
                        if (EditRateLimiter.isHeldBack(error)) {
                            EditRateLimiter.reportHeldBack(channelId, error.getClass().getSimpleName());
                        } else {
                            LOGGER.debug("lyrics edit failed", error);
                        }
                        if (!clockOnly) {
                            shownIndex = -2;
                            shownClock = "";
                        }
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
        return (lines.get(index + 1).timeMs() - positionMs) / speed > POST_EDIT_GAP_MS + TICK_MS;
    }

    private long[] upcomingLineEdits(int index, long positionMs, long now) {
        List<LyricLine> lines = lyrics.synced();
        double speed = Math.max(0.1, client.getCurrentPlaySpeed());
        long[] upcoming = new long[EditRateLimiter.MAX_EDITS_PER_WINDOW];
        int count = 0;
        for (int i = index + 1; i < lines.size() && count < upcoming.length; i++) {
            long wait = (long) ((lines.get(i).timeMs() - positionMs) / speed);
            if (wait > EditRateLimiter.WINDOW_MS) break;
            upcoming[count++] = now + Math.max(0, wait);
        }
        return java.util.Arrays.copyOf(upcoming, count);
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
        return hookFrame(track, body(null, track, lyrics, index, translation, footer, null, false, true, null));
    }

    static Container hookFrame(AudioTrack track, TextDisplay body) {
        String artwork = MusicEmbeds.thumbnailOf(track);
        Container container = artwork == null
                ? Container.of(body)
                : Container.of(Section.of(Thumbnail.fromUrl(artwork), body));
        return container.withAccentColor(ArtworkColors.of(artwork));
    }

    static TextDisplay body(@Nullable MusicPlayerClient client, AudioTrack track, Lyrics lyrics, int index,
                            @Nullable String translation, @Nullable String footer, @Nullable TranslationClient translator,
                            boolean pendingTranslation, boolean withSong, @Nullable String autoNote) {
        List<LyricLine> lines = lyrics.synced();
        String previous = lineText(lines, index - 1);
        String current = lineText(lines, index);
        String next = lineText(lines, index + 1);
        String translationBlock = translation == null ? "" : TranslationText.markdown(translation, MAX_LINE_LENGTH);
        StringBuilder text = new StringBuilder();
        text.append(previous.isBlank() ? BLANK : "*" + previous + "*").append('\n');
        text.append("## ").append(current.isBlank() ? REST : current).append('\n');
        if (!translationBlock.isBlank()) {
            text.append(translationBlock).append('\n').append(BLANK).append('\n');
        } else if (pendingTranslation) {
            text.append("-# ").append(TRANSLATING_NOTE).append('\n').append(BLANK).append('\n');
        }
        text.append(next.isBlank() ? BLANK : "*" + next + "*").append('\n');
        text.append("-# ").append(WIDTH_FILLER);
        if (withSong) {
            text.append("\n-# ").append(DiscordSafe.text(track.getInfo().author + " - " + track.getInfo().title, MAX_SONG_LENGTH));
        }
        if (footer != null) text.append("\n-# ").append(footer);
        else if (client != null) {
            text.append("\n-# ").append(playbackClock(client, track));
            if (autoNote != null) text.append("\n-# ").append(autoNote);
        }
        if ((translation != null && !translation.isBlank()) || pendingTranslation) {
            String note = LyricsPresenter.translationStatus(client, translator);
            if (!note.isBlank()) {
                for (String noteLine : note.split("\n")) {
                    text.append('\n');
                    if (!noteLine.isBlank()) text.append("-# ").append(noteLine);
                }
            }
        }
        return TextDisplay.of(text.toString());
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
