package kr.kro.backas.music.lyrics;

import kr.kro.backas.config.Config;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import kr.kro.backas.music.ArtworkColors;
import kr.kro.backas.music.ControlPanel;
import kr.kro.backas.music.MusicEmbeds;
import kr.kro.backas.music.MusicPlayerClient;
import kr.kro.backas.Main;
import kr.kro.backas.music.lyrics.sync.LyricsAutoSync;
import kr.kro.backas.music.TrackCard;
import kr.kro.backas.util.DiscordSafe;
import kr.kro.backas.util.DurationUtil;
import net.dv8tion.jda.api.components.container.Container;
import net.dv8tion.jda.api.components.container.ContainerChildComponent;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.components.section.Section;
import net.dv8tion.jda.api.components.textdisplay.TextDisplay;
import net.dv8tion.jda.api.components.thumbnail.Thumbnail;
import net.dv8tion.jda.api.entities.Message;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

public class LyricsSession {
    private static final int MAX_LINE_LENGTH = 300;
    private static final int MAX_SONG_LENGTH = 200;

    private static final Logger LOGGER = LoggerFactory.getLogger(LyricsSession.class);
    public static final long TICK_MS = Config.get().lyrics().display().tickMs();
    public static final long MIN_EDIT_INTERVAL_MS = Config.get().lyrics().display().minEditIntervalMs();
    private static final long CLOCK_EDIT_INTERVAL_MS = Config.get().lyrics().display().clockEditIntervalMs();
    private static final long POST_EDIT_GAP_MS = Config.get().lyrics().display().postEditGapMs();
    public static final long DEFAULT_OFFSET_MS = 0;
    public static final String AUTO_PENDING_NOTE = "AI 보정 중";
    public static final String AI_GENERATED_NOTE = "AI 생성 가사 · 정확하지 않을 수 있어요";
    public static final String TRANSLATING_NOTE = "번역 중...";
    public static final String PRONOUNCING_NOTE = "해석 중";
    public static final String NO_PRONUNCIATION = "-";
    private static final String BLANK = "​";
    private static final String WIDTH_FILLER = "⠀".repeat(120);
    static final String CARD_FILLER = "⠀".repeat(60);
    static final String SLOT_BLANK = "⠀";
    private static final String CLOCK_SEPARATOR = " — ";
    public static final String REST = "♪";
    private static final String FAILED_TRANSLATION = "-";

    private final MusicPlayerClient client;
    private final AudioTrack track;
    private volatile Lyrics lyrics;
    private volatile boolean convertingPartial;
    private final LyricsSurface surface;
    private final ScheduledExecutorService scheduler;
    private final TranslationClient translator;
    private final Map<Integer, String> translations;
    private final List<String> sources;
    private final String cacheKey;
    private final boolean translatable;
    private final boolean pronounceable;
    private final Map<Integer, String> pronunciations = new ConcurrentHashMap<>();
    private final AtomicBoolean pronouncing = new AtomicBoolean(false);
    private volatile boolean pronounced;
    private final long leadMs;
    private volatile long offsetMs;
    private volatile long autoOffsetMs;
    private volatile boolean autoPending;
    private volatile String originalToken;
    private volatile boolean translating;
    private volatile TranslationJobs.Job job;
    private ScheduledFuture<?> ticker;
    private int shownIndex = -2;
    private String shownTranslation;
    private String shownPronunciation;
    private GapButton shownGap;
    private String shownNote;
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
        if (lyrics.aiTimed() && lyrics.hasPlain() && this.translator != null) seedFromPlainTranslations(lyrics);
        this.translatable = isTranslatable(this.translator, lyrics);
        this.pronounceable = KoreanPronunciation.appliesTo(lyrics.synced());
        this.leadMs = EditLatency.leadMs(surface.channelId());
        this.offsetMs = offsetMs;
        TrackCard card = surface.card();
        this.originalToken = lyrics.aiTimed() && card != null
                ? Main.getLuffia().getMusicPlayerController().getLyricsConversions().registerOriginal(() -> {
                    if (!halt()) return;
                    LyricsPresenter.presentPlain(client, track, card, lyrics);
                })
                : null;
    }

    private void seedFromPlainTranslations(Lyrics lyrics) {
        List<String> plainLines = Arrays.asList(lyrics.plain().split("\\r?\\n"));
        Map<Integer, String> plain = translator.cacheFor(TranslationJobs.cacheKey("plain", plainLines));
        if (plain.isEmpty()) return;
        Map<String, String> byText = new HashMap<>();
        plain.forEach((index, text) -> {
            if (index >= 0 && index < plainLines.size() && text != null && !text.isBlank()) {
                byText.putIfAbsent(plainLines.get(index).strip(), text);
            }
        });
        int seeded = 0;
        for (int i = 0; i < sources.size(); i++) {
            if (translations.containsKey(i)) continue;
            String text = byText.get(sources.get(i).strip());
            if (text == null) continue;
            translations.put(i, text);
            seeded++;
        }
        if (seeded == 0) return;
        translator.persist(cacheKey);
        LOGGER.info("reused {} translated line(s) from the plain lyrics for {}", seeded, track.getInfo().title);
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

    public boolean updateLyrics(Lyrics replacement) {
        if (replacement == null || !replacement.hasSynced() || replacement.synced().size() != sources.size()) return false;
        for (int i = 0; i < sources.size(); i++) {
            if (!replacement.synced().get(i).text().equals(sources.get(i))) return false;
        }
        this.lyrics = replacement;
        return true;
    }

    public void setConvertingPartial(boolean partial) {
        this.convertingPartial = partial;
    }

    public void setAutoPending(boolean pending) {
        this.autoPending = pending;
    }

    @Nullable
    private String autoNote() {
        if (autoPending) return AUTO_PENDING_NOTE;
        if (convertingPartial) return LyricsConversions.LABEL_RUNNING;
        if (lyrics.aiGenerated()) return AI_GENERATED_NOTE;
        return lyrics.aiTimed() ? LyricsConversions.LABEL_DONE : null;
    }

    public void setOffsetMs(long offsetMs) {
        this.offsetMs = offsetMs;
    }

    public long getOffsetMs() {
        return offsetMs;
    }

    @Nullable
    public Long seekTarget(LyricsJumps.Jump jump) {
        if (!lyrics.hasSynced()) return null;
        double speed = Math.max(0.1, client.getCurrentPlaySpeed());
        long shift = (long) ((offsetMs + autoOffsetMs) * speed);
        Long target = LyricsJumps.target(lyrics.synced(), (long) client.getRealPositionMs() + shift, jump);
        return target == null ? null : Math.max(0, target - shift);
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
            surface.edit(view(shownIndex, translationFor(shownIndex), shownPronunciation, reason, false, null),
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
        String original = originalToken;
        if (original != null) Main.getLuffia().getMusicPlayerController().getLyricsConversions().release(original);
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

    @Nullable
    private String pronunciationFor(int index) {
        if (!pronounceable || !client.isShowingPronunciation()) return null;
        if (pronouncing.compareAndSet(false, true)) TranslationJobs.EXECUTOR.execute(this::pronounceAll);
        String source = index < 0 || index >= sources.size() || sources.get(index) == null ? "" : sources.get(index);
        if (source.isBlank()) return NO_PRONUNCIATION;
        String pronunciation = pronunciations.get(index);
        if (pronunciation != null) return pronunciation;
        return pronounced ? NO_PRONUNCIATION : PRONOUNCING_NOTE;
    }

    private void pronounceAll() {
        try {
            for (int i = 0; i < sources.size() && !stopped.get(); i++) {
                String pronunciation = KoreanPronunciation.of(sources.get(i));
                if (pronunciation != null) pronunciations.put(i, pronunciation);
            }
        } catch (RuntimeException e) {
            LOGGER.warn("failed to build the korean pronunciation for {}", track.getInfo().title, e);
        } finally {
            pronounced = true;
        }
    }

    private boolean isPendingTranslation(int index, @Nullable String translation) {
        if (translation != null || !translating || index < 0 || translations.containsKey(index)) return false;
        String text = lineText(lyrics.synced(), index);
        return LyricsLanguage.needsTranslation(text);
    }

    record GapButton(boolean outro, boolean enabled) {
    }

    private Container view(int index, @Nullable String translation, @Nullable String pronunciation, @Nullable String footer,
                           boolean pending, @Nullable GapButton gap) {
        String original = originalToken;
        ActionRow actions = original == null ? null : ActionRow.of(LyricsConversions.originalButton(original));
        Body parts = parts(client, track, lyrics, index, translation, pronunciation, footer, translator, pending,
                surface.showsSong(), autoNote());
        TrackCard card = surface.card();
        if (card != null && !parts.filler().isEmpty()) {
            Button jump = null;
            if (gap != null) {
                jump = gap.outro() ? ControlPanel.outroButton(client) : ControlPanel.gapButton(client);
                if (!gap.enabled()) jump = jump.asDisabled();
            }
            List<ContainerChildComponent> children = cardComponents(parts, jump);
            if (actions != null) children.add(actions);
            return card.frame(children);
        }
        return surface.frame(TextDisplay.of(parts.whole()), actions);
    }

    private long lyricsShift() {
        return (long) ((offsetMs + autoOffsetMs) * Math.max(0.1, client.getCurrentPlaySpeed()));
    }

    @Nullable
    private GapButton gapButton() {
        if (surface.card() == null || !lyrics.hasSynced()) return null;
        double speed = Math.max(0.1, client.getCurrentPlaySpeed());
        long trackNow = (long) (client.getRealPositionMs() + leadMs * speed);
        long shift = lyricsShift();
        long lyricNow = trackNow + shift;
        Long quiet = LyricsJumps.quietSince(lyrics.synced(), lyricNow);
        if (quiet == null) return null;
        Long next = LyricsJumps.nextSung(lyrics.synced(), lyricNow);
        LyricsJumps.ButtonState state;
        if (next != null) {
            state = LyricsJumps.buttonState(quiet, next, lyricNow);
        } else {
            long length = track.getInfo().length;
            if (track.getInfo().isStream || length <= 0) return null;
            state = LyricsJumps.buttonState(quiet - shift, length, trackNow);
        }
        if (state == LyricsJumps.ButtonState.HIDDEN) return null;
        return new GapButton(next == null, state == LyricsJumps.ButtonState.ENABLED);
    }

    public boolean inOutro() {
        if (!lyrics.hasSynced() || track.getInfo().isStream || track.getInfo().length <= 0) return false;
        long now = (long) client.getRealPositionMs();
        if (track.getInfo().length - now <= 0) return false;
        return LyricsJumps.inOutro(lyrics.synced(), now + lyricsShift());
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
            String pronunciation = pronunciationFor(index);
            boolean pending = isPendingTranslation(index, translation);
            GapButton gap = gapButton();
            String note = autoNote();
            boolean sameLine = index == shownIndex;
            boolean sameTranslation = translation == null ? shownTranslation == null : translation.equals(shownTranslation);
            boolean samePronunciation = Objects.equals(pronunciation, shownPronunciation);
            boolean clockOnly = sameLine && sameTranslation && samePronunciation && pending == shownPending
                    && Objects.equals(note, shownNote);
            boolean quick = clockOnly && !Objects.equals(gap, shownGap);
            String clock = playbackClock(client, track);
            if (quick && !hasRoomForQuickEdit(index, position)) return;
            if (clockOnly && !quick && (clock.equals(shownClock) || !hasRoomBeforeNextLine(index, position))) return;
            long now = System.currentTimeMillis();
            if (quick) {
                if (now - lastEditAt < POST_EDIT_GAP_MS) return;
            } else if (clockOnly) {
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
            shownPronunciation = pronunciation;
            shownPending = pending;
            shownGap = gap;
            shownNote = note;
            shownClock = clock;
            lastEditAt = now;
            if (!clockOnly) lastContentEditAt = now;
            surface.edit(view(index, translation, pronunciation, null, pending, gap), now + EditRateLimiter.EDIT_DEADLINE_MS)
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
                        if (!clockOnly) shownIndex = -2;
                        shownClock = "";
                    });
        } catch (RuntimeException e) {
            LOGGER.warn("lyrics tick failed", e);
            stop("가사 표시 중 오류가 발생했습니다");
        }
    }

    private boolean hasRoomForQuickEdit(int index, long positionMs) {
        List<LyricLine> lines = lyrics.synced();
        if (index + 1 >= lines.size()) return true;
        double speed = Math.max(0.1, client.getCurrentPlaySpeed());
        return (lines.get(index + 1).timeMs() - positionMs) / speed >= POST_EDIT_GAP_MS;
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
        return hookFrame(track, body(null, track, lyrics, index, translation, null, footer, null, false, true, null));
    }

    static Container hookFrame(AudioTrack track, TextDisplay body) {
        String artwork = MusicEmbeds.thumbnailOf(track);
        Container container = artwork == null
                ? Container.of(body)
                : Container.of(Section.of(Thumbnail.fromUrl(artwork), body));
        return container.withAccentColor(ArtworkColors.of(artwork));
    }

    record Body(String lyrics, String filler, String clock, @Nullable String note, String status) {
        String whole() {
            return lyrics + (filler.isEmpty() ? "" : "\n" + filler) + (clock.isEmpty() ? "" : "\n-# " + clock)
                    + (note == null ? "" : "\n-# " + note) + status;
        }

        String block() {
            if (note != null) return "-# " + clock + "\n-# " + note + status;
            return "-# " + SLOT_BLANK + (clock.isEmpty() ? "" : "\n-# " + clock) + status;
        }
    }

    static List<ContainerChildComponent> cardComponents(Body parts, @Nullable Button jump) {
        List<ContainerChildComponent> children = new ArrayList<>();
        children.add(TextDisplay.of(parts.lyrics() + "\n-# " + CARD_FILLER));
        TextDisplay block = TextDisplay.of(parts.block());
        children.add(jump == null ? block : Section.of(jump, block));
        return children;
    }

    static TextDisplay body(@Nullable MusicPlayerClient client, AudioTrack track, Lyrics lyrics, int index,
                            @Nullable String translation, @Nullable String pronunciation, @Nullable String footer,
                            @Nullable TranslationClient translator, boolean pendingTranslation, boolean withSong,
                            @Nullable String autoNote) {
        return TextDisplay.of(parts(client, track, lyrics, index, translation, pronunciation, footer, translator,
                pendingTranslation, withSong, autoNote).whole());
    }

    static Body parts(@Nullable MusicPlayerClient client, AudioTrack track, Lyrics lyrics, int index,
                      @Nullable String translation, @Nullable String pronunciation, @Nullable String footer,
                      @Nullable TranslationClient translator, boolean pendingTranslation, boolean withSong,
                      @Nullable String autoNote) {
        List<LyricLine> lines = lyrics.synced();
        String previous = lineText(lines, index - 1);
        String current = lineText(lines, index);
        String next = lineText(lines, index + 1);
        translation = translation == null ? null : DiscordSafe.text(translation, MAX_LINE_LENGTH);
        StringBuilder text = new StringBuilder();
        text.append(previous.isBlank() ? BLANK : "*" + previous + "*").append('\n');
        text.append("## ").append(current.isBlank() ? REST : current).append('\n');
        if (NO_PRONUNCIATION.equals(pronunciation) || PRONOUNCING_NOTE.equals(pronunciation)) {
            text.append("-# ").append(pronunciation).append('\n');
        } else if (pronunciation != null && !pronunciation.isBlank()) {
            text.append("-# *").append(DiscordSafe.escaped(pronunciation, MAX_LINE_LENGTH)).append("*\n");
        }
        if (translation != null && !translation.isBlank()) {
            text.append("-# ").append(translation).append('\n').append(BLANK).append('\n');
        } else if (pendingTranslation) {
            text.append("-# ").append(TRANSLATING_NOTE).append('\n').append(BLANK).append('\n');
        } else {
            text.append(BLANK).append('\n');
        }
        text.append(next.isBlank() ? BLANK : "*" + next + "*");
        String filler = "-# " + WIDTH_FILLER;
        if (withSong) {
            text.append('\n').append(filler);
            text.append("\n-# ").append(DiscordSafe.text(track.getInfo().author + " - " + track.getInfo().title, MAX_SONG_LENGTH));
            filler = "";
        }
        String clock = "";
        String note = null;
        if (footer != null) {
            clock = footer;
        } else if (client != null) {
            clock = playbackClock(client, track);
            note = autoNote == null || autoNote.isBlank() ? null : autoNote;
        }
        StringBuilder status = new StringBuilder();
        if ((translation != null && !translation.isBlank()) || pendingTranslation) {
            String statusText = LyricsPresenter.translationStatus(client, translator);
            if (!statusText.isBlank()) {
                for (String statusLine : statusText.split("\n")) {
                    status.append('\n');
                    if (!statusLine.isBlank()) status.append("-# ").append(statusLine);
                }
            }
        }
        return new Body(text.toString(), filler, clock, note, status.toString());
    }

    static String playbackClock(MusicPlayerClient client, AudioTrack track) {
        long length = track.getInfo().length;
        long position = Math.max(0, (long) client.getRealPositionMs());
        if (track.getInfo().isStream || length <= 0) return DurationUtil.formatClock(position / 1000);
        return DurationUtil.formatClock(Math.min(position, length) / 1000) + CLOCK_SEPARATOR + DurationUtil.formatClock(length / 1000);
    }

    private static String lineText(List<LyricLine> lines, int index) {
        if (index < 0 || index >= lines.size()) return "";
        return DiscordSafe.text(lines.get(index).text().strip(), MAX_LINE_LENGTH);
    }
}
