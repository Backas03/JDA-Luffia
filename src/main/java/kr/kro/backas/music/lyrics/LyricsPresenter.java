package kr.kro.backas.music.lyrics;

import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import kr.kro.backas.Main;
import kr.kro.backas.SharedConstant;
import kr.kro.backas.music.ArtworkColors;
import kr.kro.backas.music.MusicEmbeds;
import kr.kro.backas.music.MusicPlayerClient;
import kr.kro.backas.music.MusicPlayerController;
import kr.kro.backas.music.TrackCard;
import kr.kro.backas.util.DiscordSafe;
import kr.kro.backas.util.MemberUtil;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.container.Container;
import net.dv8tion.jda.api.components.container.ContainerChildComponent;
import net.dv8tion.jda.api.components.textdisplay.TextDisplay;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.interactions.InteractionHook;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.Supplier;

public final class LyricsPresenter {

    private static final Logger LOGGER = LoggerFactory.getLogger(LyricsPresenter.class);
    private static final int EMBED_TEXT_LIMIT = 4000;
    private static final int MAX_LINE_LENGTH = 300;
    private static final int MAX_AUTHOR_LENGTH = 250;
    private static final int MESSAGE_TEXT_BUDGET = 5800;
    static final int CARD_TEXT_BUDGET = 3500;
    static final int FULL_PREVIEW_LINES = 6;
    private static final String TRUNCATED_NOTE = "… (이하 생략)";
    private static final String FULL_LYRICS_NOTE = "타임스탬프 가사가 없어 전체 가사로 표시합니다";
    private static final long CLOCK_INTERVAL_MS = 900;

    public static String translationStatus(@Nullable MusicPlayerClient client, @Nullable TranslationClient translator) {
        if (translator == null || !isDebugDisplay()) return "";
        String model = translator.getModelName();
        if (model.isBlank()) return "";
        return translator.computeSummary() + " | " + progressDisplay(client);
    }

    private static boolean isDebugDisplay() {
        return Main.getLuffia().getMusicPlayerController().getAiGuard().isDebug();
    }

    private static String translatingNote(@Nullable MusicPlayerClient client, @Nullable TranslationClient translator) {
        String status = translationStatus(client, translator);
        return status.isBlank() ? LyricsSession.TRANSLATING_NOTE : LyricsSession.TRANSLATING_NOTE + "\n" + status;
    }

    private static final class SongProgress {
        private final String id;
        private volatile TranslationJobs.Job job;
        private volatile boolean complete;

        private SongProgress(String id) {
            this.id = id;
        }

        private float fraction() {
            if (complete) return 1f;
            TranslationJobs.Job current = job;
            return current == null ? 0f : current.progress();
        }
    }

    private static final Map<MusicPlayerClient, List<SongProgress>> PROGRESS_PLANS = new ConcurrentHashMap<>();
    private static final Object PROGRESS_LOCK = new Object();

    private static void updateProgressPlan(MusicPlayerClient client, @Nullable AudioTrack current, List<AudioTrack> targets) {
        synchronized (PROGRESS_LOCK) {
            Map<String, SongProgress> known = new ConcurrentHashMap<>();
            for (SongProgress entry : PROGRESS_PLANS.getOrDefault(client, List.of())) known.put(entry.id, entry);
            List<SongProgress> plan = new ArrayList<>();
            Set<String> seen = ConcurrentHashMap.newKeySet();
            List<AudioTrack> tracks = new ArrayList<>();
            if (current != null) tracks.add(current);
            tracks.addAll(targets);
            for (AudioTrack track : tracks) {
                String id = track.getIdentifier();
                if (!seen.add(id)) continue;
                SongProgress entry = known.get(id);
                plan.add(entry == null ? new SongProgress(id) : entry);
            }
            PROGRESS_PLANS.put(client, List.copyOf(plan));
        }
    }

    private static SongProgress progressEntry(MusicPlayerClient client, AudioTrack track) {
        String id = track.getIdentifier();
        synchronized (PROGRESS_LOCK) {
            List<SongProgress> current = PROGRESS_PLANS.getOrDefault(client, List.of());
            for (SongProgress entry : current) {
                if (entry.id.equals(id)) return entry;
            }
            SongProgress created = new SongProgress(id);
            List<SongProgress> plan = new ArrayList<>(current);
            plan.add(created);
            PROGRESS_PLANS.put(client, List.copyOf(plan));
            return created;
        }
    }

    static void reportSongJob(MusicPlayerClient client, AudioTrack track, TranslationJobs.Job job) {
        progressEntry(client, track).job = job;
    }

    static void reportSongComplete(MusicPlayerClient client, AudioTrack track) {
        progressEntry(client, track).complete = true;
    }

    private static String progressDisplay(@Nullable MusicPlayerClient client) {
        List<SongProgress> plan = client == null ? List.of() : PROGRESS_PLANS.getOrDefault(client, List.of());
        if (plan.isEmpty()) return "번역 준비 중 ...";
        float sum = 0f;
        int completed = 0;
        for (SongProgress entry : plan) {
            float fraction = entry.fraction();
            sum += fraction;
            if (fraction >= 1f) completed++;
        }
        java.text.DecimalFormat format = new java.text.DecimalFormat("0.00");
        return format.format(100.0 * sum / plan.size()) + "% (" + completed + "/" + plan.size() + ")";
    }

    private LyricsPresenter() {
    }

    private static CompletableFuture<Lyrics> lookup(AudioTrack track) {
        MusicPlayerController controller = Main.getLuffia().getMusicPlayerController();
        return CompletableFuture.supplyAsync(() -> {
            try {
                return controller.getLyricsClient().find(track.getInfo());
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }, TranslationJobs.EXECUTOR);
    }

    private static boolean hasNoLyrics(@Nullable Lyrics lyrics) {
        return lyrics == null || lyrics.instrumental() || (!lyrics.hasPlain() && !lyrics.hasSynced());
    }

    private static void replaceSession(MusicPlayerClient client, AudioTrack track) {
        LyricsSession previous = client.getLyricsSession();
        if (previous != null && !previous.isForTrack(track)) client.dismissLyrics();
        else client.stopLyrics("새 가사 표시로 대체되었습니다");
    }

    private static void startSession(MusicPlayerClient client, AudioTrack track, Lyrics lyrics, LyricsSurface surface, long offsetMs) {
        MusicPlayerController controller = Main.getLuffia().getMusicPlayerController();
        LyricsSession session = new LyricsSession(client, track, lyrics, surface,
                controller.getScheduler(), controller.getTranslationClient(), offsetMs);
        client.setLyricsSession(session);
        session.start();
    }

    public static void presentOnCard(MusicPlayerClient client, AudioTrack track, TrackCard card, long offsetMs) {
        TranslationClient translator = Main.getLuffia().getMusicPlayerController().getTranslationClient();
        lookup(track).whenComplete((lyrics, error) -> {
            if (error != null) {
                Throwable cause = error.getCause() == null ? error : error.getCause();
                LOGGER.warn("lyrics lookup failed for {}", track.getInfo().title, cause);
                card.close();
                return;
            }
            if (hasNoLyrics(lyrics)) {
                card.close();
                prefetchNext(client);
                return;
            }
            if (!client.isCurrentTrack(track)) {
                card.close();
                return;
            }
            if (lyrics.hasSynced()) {
                replaceSession(client, track);
                startSession(client, track, lyrics, LyricsSurface.ofCard(card), offsetMs);
                return;
            }
            showFull(client, track, lyrics, null, true,
                    new CardFullView(card, Main.getLuffia().getMusicPlayerController().getLyricsExpansions()), translator);
        });
    }

    public static void presentViaHook(MusicPlayerClient client, AudioTrack track, InteractionHook hook,
                                      long offsetMs, boolean liveWanted, @Nullable Member requester) {
        TranslationClient translator = Main.getLuffia().getMusicPlayerController().getTranslationClient();
        Function<List<MessageEmbed>, CompletableFuture<Message>> sendEmbeds = embeds -> hook.editOriginalEmbeds(embeds).submit();
        lookup(track).whenComplete((lyrics, error) -> {
            if (error != null) {
                Throwable cause = error.getCause() == null ? error : error.getCause();
                LOGGER.warn("lyrics lookup failed for {}", track.getInfo().title, cause);
                if (requester != null) {
                    sendEmbeds.apply(List.of(MusicEmbeds.error(requester, "가사를 불러오지 못했습니다", cause.getMessage()).build()));
                }
                return;
            }
            if (hasNoLyrics(lyrics)) {
                if (requester != null) {
                    sendEmbeds.apply(List.of(MusicEmbeds.error(requester, "가사를 찾지 못했습니다",
                            track.getInfo().author + " - " + track.getInfo().title).build()));
                }
                prefetchNext(client);
                return;
            }
            if (!client.isCurrentTrack(track)) return;
            if (liveWanted && lyrics.hasSynced()) {
                replaceSession(client, track);
                String initialTranslation = LyricsSession.isTranslatable(translator, lyrics) ? LyricsSession.REST : null;
                Container initial = LyricsSession.buildView(track, lyrics, -1, initialTranslation, "가사 동기화 준비 중");
                hook.editOriginalComponents(initial).useComponentsV2(true).submit().whenComplete((message, sendError) -> {
                    if (sendError != null || message == null) {
                        LOGGER.warn("failed to send lyrics message", sendError);
                        return;
                    }
                    if (!client.isCurrentTrack(track)) return;
                    startSession(client, track, lyrics, LyricsSurface.ofMessage(message, track), offsetMs);
                });
                return;
            }
            showFull(client, track, lyrics, requester, liveWanted, new EmbedFullView(sendEmbeds), translator);
        });
    }

    private interface FullView {
        CompletableFuture<?> show(AudioTrack track, List<String> lines, @Nullable Map<Integer, String> translations,
                                  String footer, @Nullable String note, long deadlineAt);

        long channelId();

        void dismissWhenTrackEnds(MusicPlayerClient client, AudioTrack track);
    }

    private static final class EmbedFullView implements FullView {
        private final Function<List<MessageEmbed>, CompletableFuture<Message>> send;
        private volatile Message message;

        private EmbedFullView(Function<List<MessageEmbed>, CompletableFuture<Message>> send) {
            this.send = send;
        }

        @Override
        public CompletableFuture<?> show(AudioTrack track, List<String> lines, @Nullable Map<Integer, String> translations,
                                         String footer, @Nullable String note, long deadlineAt) {
            List<MessageEmbed> embeds = buildFullEmbeds(track, lines, translations, footer, note);
            Message current = message;
            if (current == null) {
                return send.apply(embeds).thenApply(sent -> {
                    message = sent;
                    return sent;
                });
            }
            return current.editMessageEmbeds(embeds).deadline(deadlineAt).submit();
        }

        @Override
        public long channelId() {
            Message current = message;
            return current == null ? 0 : current.getChannel().getIdLong();
        }

        @Override
        public void dismissWhenTrackEnds(MusicPlayerClient client, AudioTrack track) {
            Message current = message;
            if (current != null) deleteWhenTrackEnds(client, track, current);
        }
    }

    private static final class CardFullView implements FullView, LyricsExpansions.Expandable {
        private final TrackCard card;
        private final LyricsExpansions expansions;
        private final String token;
        private volatile boolean expanded;
        private volatile List<String> lines = List.of();
        private volatile Map<Integer, String> translations;
        private volatile String footer = "";
        private volatile String note;

        private CardFullView(TrackCard card, LyricsExpansions expansions) {
            this.card = card;
            this.expansions = expansions;
            this.token = expansions.register(this);
        }

        @Override
        public boolean isExpanded() {
            return expanded;
        }

        @Override
        public void setExpanded(boolean expanded) {
            this.expanded = expanded;
        }

        @Override
        public Container render() {
            List<String> all = lines;
            boolean foldable = all.size() > FULL_PREVIEW_LINES;
            List<String> shown = foldable && !expanded ? all.subList(0, FULL_PREVIEW_LINES) : all;
            String hint = foldable && !expanded ? "\n-# 외 " + (all.size() - FULL_PREVIEW_LINES) + "줄" : "";
            List<ContainerChildComponent> body = new ArrayList<>();
            body.add(TextDisplay.of(fullBody(shown, translations, footer, note, CARD_TEXT_BUDGET, hint)));
            if (foldable) body.add(ActionRow.of(LyricsExpansions.button(token, expanded)));
            return card.frame(body);
        }

        @Override
        public CompletableFuture<?> show(AudioTrack track, List<String> lines, @Nullable Map<Integer, String> translations,
                                         String footer, @Nullable String note, long deadlineAt) {
            if (card.isClosed()) {
                expansions.release(token);
                return CompletableFuture.completedFuture(null);
            }
            this.lines = lines;
            this.translations = translations;
            this.footer = footer;
            this.note = note;
            return card.edit(render(), deadlineAt);
        }

        @Override
        public long channelId() {
            return card.channelId();
        }

        @Override
        public void dismissWhenTrackEnds(MusicPlayerClient client, AudioTrack track) {
        }
    }

    private static void showFull(MusicPlayerClient client, AudioTrack track, Lyrics lyrics, @Nullable Member requester, boolean liveWanted,
                                 FullView view, TranslationClient translator) {
        List<String> lines = fullLines(lyrics);
        String footer = liveWanted ? FULL_LYRICS_NOTE : SharedConstant.RELEASE_VERSION;
        if (requester != null) footer += " · 요청: " + MemberUtil.getName(requester);
        String finalFooter = footer;
        List<LyricLine> asLines = new ArrayList<>();
        for (String line : lines) asLines.add(new LyricLine(0, line));
        boolean willTranslate = translator != null && translator.isEnabled()
                && !LyricsLanguage.KOREAN.equals(LyricsLanguage.detect(asLines));
        if (translator != null && translator.isEnabled() && !willTranslate) reportSongComplete(client, track);
        String initialNote = willTranslate ? translatingNote(client, translator) : null;
        boolean dismissOnEnd = requester == null && liveWanted;
        boolean showClock = liveWanted;
        long firstDeadline = System.currentTimeMillis() + EditRateLimiter.EDIT_DEADLINE_MS;
        view.show(track, lines, null, clockFooter(client, track, finalFooter, showClock), initialNote, firstDeadline).whenComplete((sent, sendError) -> {
            if (sendError != null) {
                LOGGER.warn("failed to send full lyrics", sendError);
                return;
            }
            if (dismissOnEnd) view.dismissWhenTrackEnds(client, track);
            if (!willTranslate && !showClock) return;
            String cacheKey = TranslationJobs.cacheKey("plain", lines);
            Map<Integer, String> cache = willTranslate ? translator.cacheFor(cacheKey) : null;
            AtomicReference<TranslationJobs.Job> jobRef = new AtomicReference<>();
            ProgressiveEditor editor = new ProgressiveEditor(view.channelId(),
                    showClock ? CLOCK_INTERVAL_MS : ProgressiveEditor.MIN_INTERVAL_MS, () -> {
                String note = null;
                if (willTranslate) {
                    TranslationJobs.Job current = jobRef.get();
                    boolean translating = current == null || !current.done().isDone();
                    note = translating
                            ? translatingNote(client, translator)
                            : (cache.isEmpty() ? "번역에 실패했습니다" : translationStatus(client, translator));
                }
                return view.show(track, lines, cache, clockFooter(client, track, finalFooter, showClock), note,
                        System.currentTimeMillis() + EditRateLimiter.EDIT_DEADLINE_MS);
            });
            if (showClock) {
                startClock(client, track, editor, () -> {
                    if (willTranslate || dismissOnEnd) return;
                    view.show(track, lines, null, finalFooter, null, System.currentTimeMillis() + EditRateLimiter.EDIT_DEADLINE_MS)
                            .whenComplete((result, error) -> {
                                if (error != null) LOGGER.debug("failed to finalize full lyrics", error);
                            });
                });
            }
            if (!willTranslate) return;
            CompletableFuture.runAsync(() -> {
                prefetchNext(client);
                TranslationJobs.Job job = TranslationJobs.submit(translator, cacheKey, lines, true,
                        (index, text) -> editor.requestEdit(), TranslationJobs.songContext(track));
                jobRef.set(job);
                reportSongJob(client, track, job);
                ScheduledFuture<?> heartbeat = Main.getLuffia().getMusicPlayerController().getScheduler()
                        .scheduleAtFixedRate(() -> {
                            if (isDebugDisplay()) editor.requestIdleRefresh(5000);
                        }, 2, 1, TimeUnit.SECONDS);
                boolean demoted = false;
                while (!job.done().isDone()) {
                    if (!demoted && !client.isCurrentTrack(track)) {
                        demoted = true;
                        if (requester == null) job.cancel();
                        else job.demote();
                    }
                    try {
                        job.done().get(500, TimeUnit.MILLISECONDS);
                    } catch (TimeoutException ignored) {
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    } catch (ExecutionException ignored) {
                        break;
                    }
                }
                Map<Integer, String> translations = cache;
                if (job.isCancelled()) {
                    heartbeat.cancel(false);
                    editor.cancel();
                    return;
                }
                prefetchNext(client);
                editor.requestEdit();
                while (client.isCurrentTrack(track)) {
                    try {
                        Thread.sleep(2000);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
                heartbeat.cancel(false);
                editor.cancel();
                if (dismissOnEnd) return;
                String doneNote = translations.isEmpty()
                        ? "번역에 실패했습니다"
                        : translationStatus(client, translator);
                view.show(track, lines, translations, finalFooter, doneNote, System.currentTimeMillis() + EditRateLimiter.EDIT_DEADLINE_MS)
                        .whenComplete((result, error) -> {
                            if (error != null) LOGGER.debug("failed to attach translations", error);
                        });
            }, TranslationJobs.EXECUTOR);
        });
    }

    private static String clockFooter(MusicPlayerClient client, AudioTrack track, String footer, boolean showClock) {
        if (!showClock || !client.isCurrentTrack(track)) return footer;
        return LyricsSession.playbackClock(client, track) + "\n" + footer;
    }

    private static void startClock(MusicPlayerClient client, AudioTrack track, ProgressiveEditor editor, Runnable onEnd) {
        ScheduledExecutorService scheduler = Main.getLuffia().getMusicPlayerController().getScheduler();
        AtomicReference<ScheduledFuture<?>> ticker = new AtomicReference<>();
        AtomicReference<String> shown = new AtomicReference<>(LyricsSession.playbackClock(client, track));
        AtomicBoolean ended = new AtomicBoolean(false);
        ticker.set(scheduler.scheduleAtFixedRate(() -> {
            try {
                if (!client.isCurrentTrack(track)) {
                    if (!ended.compareAndSet(false, true)) return;
                    ScheduledFuture<?> self = ticker.get();
                    if (self != null) self.cancel(false);
                    editor.cancel();
                    onEnd.run();
                    return;
                }
                String clock = LyricsSession.playbackClock(client, track);
                if (!clock.equals(shown.getAndSet(clock))) editor.requestExtraEdit();
            } catch (RuntimeException e) {
                LOGGER.warn("full lyrics clock update failed for {}", track.getInfo().title, e);
            }
        }, 1, 1, TimeUnit.SECONDS));
    }

    private static void deleteWhenTrackEnds(MusicPlayerClient client, AudioTrack track, Message message) {
        ScheduledExecutorService scheduler = Main.getLuffia().getMusicPlayerController().getScheduler();
        AtomicBoolean deleted = new AtomicBoolean(false);
        AtomicReference<ScheduledFuture<?>> watcher = new AtomicReference<>();
        watcher.set(scheduler.scheduleAtFixedRate(() -> {
            if (client.isCurrentTrack(track) || !deleted.compareAndSet(false, true)) return;
            ScheduledFuture<?> self = watcher.get();
            if (self != null) self.cancel(false);
            LyricsSession.deleteMessage(message);
        }, 1, 1, TimeUnit.SECONDS));
    }

    private static final class ProgressiveEditor {
        private static final long MIN_INTERVAL_MS = 2000;
        private static final long IN_FLIGHT_RETRY_MS = 250;
        private static final long LIMIT_RETRY_MS = 100;
        private final long channelId;
        private final long minIntervalMs;
        private final Supplier<CompletableFuture<?>> edit;
        private final ScheduledExecutorService scheduler = Main.getLuffia().getMusicPlayerController().getScheduler();
        private long lastEditAt;
        private ScheduledFuture<?> pending;
        private long inFlightSince;
        private boolean cancelled;

        ProgressiveEditor(long channelId, long minIntervalMs, Supplier<CompletableFuture<?>> edit) {
            this.channelId = channelId;
            this.minIntervalMs = minIntervalMs;
            this.edit = edit;
            this.lastEditAt = System.currentTimeMillis();
        }

        synchronized void requestEdit() {
            if (cancelled || pending != null) return;
            long wait = Math.max(0, lastEditAt + minIntervalMs - System.currentTimeMillis());
            pending = scheduler.schedule(this::run, wait, TimeUnit.MILLISECONDS);
        }

        synchronized void requestIdleRefresh(long idleMs) {
            if (cancelled || pending != null) return;
            if (System.currentTimeMillis() - lastEditAt < idleMs) return;
            requestExtraEdit();
        }

        synchronized void requestExtraEdit() {
            if (EditRateLimiter.extrasAllowed(channelId)) requestEdit();
        }

        private void run() {
            final long started = System.currentTimeMillis();
            synchronized (this) {
                pending = null;
                if (cancelled) return;
                if (inFlightSince != 0) {
                    if (started - inFlightSince < EditRateLimiter.IN_FLIGHT_TIMEOUT_MS) {
                        pending = scheduler.schedule(this::run, IN_FLIGHT_RETRY_MS, TimeUnit.MILLISECONDS);
                        return;
                    }
                    EditRateLimiter.reportHeldBack(channelId, "no response to an edit");
                }
                if (!EditRateLimiter.tryAcquire(channelId)) {
                    long retry = Math.max(LIMIT_RETRY_MS, EditRateLimiter.millisUntilNext(channelId));
                    pending = scheduler.schedule(this::run, retry, TimeUnit.MILLISECONDS);
                    return;
                }
                lastEditAt = started;
                inFlightSince = started;
            }
            CompletableFuture<?> sent;
            try {
                sent = edit.get();
            } catch (RuntimeException e) {
                sent = CompletableFuture.failedFuture(e);
            }
            sent.whenComplete((result, error) -> {
                synchronized (this) {
                    if (inFlightSince == started) inFlightSince = 0;
                }
                if (error == null) return;
                if (EditRateLimiter.isHeldBack(error)) EditRateLimiter.reportHeldBack(channelId, error.getClass().getSimpleName());
                else LOGGER.debug("progressive lyrics edit failed", error);
            });
        }

        synchronized void cancel() {
            cancelled = true;
            if (pending != null) pending.cancel(false);
        }
    }

    private static final Set<String> PREFETCHING = ConcurrentHashMap.newKeySet();

    public static final int PREFETCH_COUNT = 3;
    public static final int PREFETCH_COUNT_FAST = 20;

    private static final Map<MusicPlayerClient, Boolean> PREFETCH_RERUN = new ConcurrentHashMap<>();
    private static final Set<MusicPlayerClient> PREFETCH_RUNNING = ConcurrentHashMap.newKeySet();

    private static List<AudioTrack> prefetchTargets(MusicPlayerClient client, TranslationClient translator) {
        List<AudioTrack> queue = client.getUpcomingTracks();
        int count = translator.hasUsableGpu() ? PREFETCH_COUNT_FAST : PREFETCH_COUNT;
        return new ArrayList<>(queue.subList(0, Math.min(count, queue.size())));
    }

    private static final int LOOKUP_THREADS = 3;
    private static final int WARMED_SIZE = 2000;
    private static final ExecutorService LOOKUP_EXECUTOR = Executors.newFixedThreadPool(LOOKUP_THREADS, runnable -> {
        Thread thread = new Thread(runnable, "lyrics-lookup");
        thread.setDaemon(true);
        return thread;
    });
    private static final Set<String> WARMED = Collections.newSetFromMap(Collections.synchronizedMap(
            new LinkedHashMap<>(64, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
                    return size() > WARMED_SIZE;
                }
            }));

    private static void warmLyrics(MusicPlayerController controller, List<AudioTrack> tracks) {
        for (AudioTrack track : tracks) {
            ArtworkColors.warm(MusicEmbeds.thumbnailOf(track));
            String identifier = track.getIdentifier();
            if (!WARMED.add(identifier)) continue;
            LOOKUP_EXECUTOR.execute(() -> {
                try {
                    controller.getLyricsClient().find(track.getInfo());
                } catch (IOException | RuntimeException e) {
                    WARMED.remove(identifier);
                    LOGGER.debug("lyrics lookup ahead of time failed for {}", track.getInfo().title, e);
                }
            });
        }
    }

    public static void prefetchNext(MusicPlayerClient client) {
        MusicPlayerController controller = Main.getLuffia().getMusicPlayerController();
        TranslationClient translator = controller.getTranslationClient();
        List<AudioTrack> queue = client.getUpcomingTracks();
        boolean llmIsSlow = translator != null && translator.isEnabled() && !translator.hasUsableGpu();
        warmLyrics(controller, queue.subList(0, Math.min(llmIsSlow ? PREFETCH_COUNT : PREFETCH_COUNT_FAST, queue.size())));
        if (translator == null || !translator.isEnabled()) {
            LOGGER.info("lyrics prefetch skipped: translator disabled");
            return;
        }
        if (!PREFETCH_RUNNING.add(client)) {
            PREFETCH_RERUN.put(client, Boolean.TRUE);
            updateProgressPlan(client, client.getCurrentPlaying(), prefetchTargets(client, translator));
            LOGGER.info("lyrics prefetch already running, will rerun when it finishes");
            return;
        }
        TranslationJobs.EXECUTOR.execute(() -> {
            try {
                do {
                    PREFETCH_RERUN.remove(client);
                    List<AudioTrack> targets = prefetchTargets(client, translator);
                    updateProgressPlan(client, client.getCurrentPlaying(), targets);
                    int parallelism = translator.parallelism();
                    LOGGER.info("lyrics prefetch pass over {} queued track(s), {} at a time", targets.size(), parallelism);
                    Semaphore permits = new Semaphore(parallelism);
                    List<CompletableFuture<Void>> running = new ArrayList<>();
                    for (AudioTrack next : targets) {
                        if (!client.hasJoinedToVoiceChannel()) break;
                        permits.acquireUninterruptibly();
                        running.add(CompletableFuture.runAsync(() -> {
                            try {
                                prefetchTrack(client, controller, translator, next);
                            } catch (RuntimeException e) {
                                LOGGER.warn("lyrics prefetch failed for {}", next.getInfo().title, e);
                            } finally {
                                permits.release();
                            }
                        }, TranslationJobs.EXECUTOR));
                    }
                    CompletableFuture.allOf(running.toArray(new CompletableFuture[0])).join();
                } while (PREFETCH_RERUN.remove(client) != null && client.hasJoinedToVoiceChannel());
            } finally {
                PREFETCH_RUNNING.remove(client);
            }
        });
    }

    private static void prefetchTrack(MusicPlayerClient client, MusicPlayerController controller, TranslationClient translator, AudioTrack next) {
        String key = next.getIdentifier();
        if (!PREFETCHING.add(key)) return;
        try {
            LOGGER.info("prefetching lyrics translation for {}", next.getInfo().title);
            Lyrics lyrics = controller.getLyricsClient().find(next.getInfo());
            if (lyrics == null || lyrics.instrumental()) {
                LOGGER.info("no lyrics to prefetch for {}", next.getInfo().title);
                reportSongComplete(client, next);
                return;
            }
            if (lyrics.hasSynced()) {
                List<String> lines = new ArrayList<>();
                lyrics.synced().forEach(line -> lines.add(line.text()));
                if (LyricsLanguage.KOREAN.equals(LyricsLanguage.detect(lyrics.synced()))) {
                    reportSongComplete(client, next);
                    return;
                }
                TranslationJobs.Job job = TranslationJobs.submit(translator, TranslationJobs.cacheKey("synced", lines), lines, false, null,
                        TranslationJobs.songContext(next));
                reportSongJob(client, next, job);
                job.done().join();
            } else if (lyrics.hasPlain()) {
                List<String> lines = fullLines(lyrics);
                List<LyricLine> asLines = new ArrayList<>();
                for (String line : lines) asLines.add(new LyricLine(0, line));
                if (LyricsLanguage.KOREAN.equals(LyricsLanguage.detect(asLines))) {
                    reportSongComplete(client, next);
                    return;
                }
                TranslationJobs.Job job = TranslationJobs.submit(translator, TranslationJobs.cacheKey("plain", lines), lines, false, null,
                        TranslationJobs.songContext(next));
                reportSongJob(client, next, job);
                job.done().join();
            } else {
                reportSongComplete(client, next);
            }
            LOGGER.info("prefetched lyrics translation for {}", next.getInfo().title);
        } catch (Exception e) {
            LOGGER.warn("lyrics prefetch failed for {}: {}", next.getInfo().title, e.toString());
        } finally {
            PREFETCHING.remove(key);
        }
    }

    private static List<String> fullLines(Lyrics lyrics) {
        if (lyrics.hasPlain()) {
            return Arrays.asList(lyrics.plain().split("\\r?\\n"));
        }
        List<String> lines = new ArrayList<>();
        lyrics.synced().forEach(line -> lines.add(line.text()));
        return lines;
    }

    static String fullBody(List<String> lines, @Nullable Map<Integer, String> translations, String footer, @Nullable String note, int budget) {
        return fullBody(lines, translations, footer, note, budget, "");
    }

    static String fullBody(List<String> lines, @Nullable Map<Integer, String> translations, String footer, @Nullable String note,
                           int budget, String hint) {
        StringBuilder tail = new StringBuilder();
        if (!hint.isEmpty()) tail.append(hint.startsWith("\n") ? hint.substring(1) : hint).append('\n');
        tail.append("-# ").append(footer.replace("\n", "\n-# "));
        if (note != null && !note.isBlank()) {
            for (String noteLine : note.split("\n")) {
                tail.append('\n');
                if (!noteLine.isBlank()) tail.append("-# ").append(noteLine);
            }
        }
        int limit = budget - tail.length() - TRUNCATED_NOTE.length() - 2;
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < lines.size(); i++) {
            String entry = DiscordSafe.text(lines.get(i), MAX_LINE_LENGTH);
            String translation = translations == null ? null : DiscordSafe.text(translations.get(i), MAX_LINE_LENGTH);
            if (translation != null && !translation.isBlank()) entry += "\n-# " + translation;
            if (text.length() + entry.length() + 1 > limit) {
                text.append(TRUNCATED_NOTE).append('\n');
                break;
            }
            text.append(entry).append('\n');
        }
        return text.append('\n').append(tail).toString();
    }

    private static List<MessageEmbed> buildFullEmbeds(AudioTrack track, List<String> lines,
                                                      @Nullable Map<Integer, String> translations, String footer,
                                                      @Nullable String note) {
        List<String> pages = paginate(lines, translations);
        if (note != null && !note.isBlank()) {
            StringBuilder small = new StringBuilder();
            for (String noteLine : note.split("\n")) {
                small.append('\n');
                if (!noteLine.isBlank()) small.append("-# ").append(noteLine);
            }
            String suffix = small.toString();
            if (!pages.isEmpty() && pages.get(pages.size() - 1).length() + suffix.length() <= 4096) {
                pages.set(pages.size() - 1, pages.get(pages.size() - 1) + suffix);
            } else {
                pages.add(suffix.substring(1));
            }
        }
        List<MessageEmbed> embeds = new ArrayList<>();
        for (int i = 0; i < pages.size(); i++) {
            EmbedBuilder builder = new EmbedBuilder()
                    .setColor(MusicEmbeds.PRIMARY)
                    .setDescription(pages.get(i));
            if (i == 0) {
                builder.setAuthor(DiscordSafe.text(track.getInfo().author + " - " + track.getInfo().title, MAX_AUTHOR_LENGTH), track.getInfo().uri)
                        .setThumbnail(MusicEmbeds.thumbnailOf(track));
            }
            if (i == pages.size() - 1) {
                builder.setFooter(footer);
            }
            embeds.add(builder.build());
        }
        return embeds;
    }

    private static List<String> paginate(List<String> lines, @Nullable Map<Integer, String> translations) {
        List<String> pages = new ArrayList<>();
        StringBuilder page = new StringBuilder();
        int used = 0;
        for (int i = 0; i < lines.size(); i++) {
            String entry = DiscordSafe.text(lines.get(i), MAX_LINE_LENGTH);
            String translation = translations == null ? null : DiscordSafe.text(translations.get(i), MAX_LINE_LENGTH);
            if (translation != null && !translation.isBlank()) {
                entry += "\n-# " + translation;
            }
            int needed = entry.length() + 1;
            if (used + needed + TRUNCATED_NOTE.length() > MESSAGE_TEXT_BUDGET) {
                page.append(TRUNCATED_NOTE);
                break;
            }
            if (page.length() + needed > EMBED_TEXT_LIMIT) {
                pages.add(page.toString());
                page = new StringBuilder();
            }
            page.append(entry).append('\n');
            used += needed;
        }
        if (!page.isEmpty()) pages.add(page.toString());
        return pages;
    }
}
