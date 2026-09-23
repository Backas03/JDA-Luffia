package kr.kro.backas.music.lyrics;

import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import kr.kro.backas.Main;
import kr.kro.backas.SharedConstant;
import kr.kro.backas.music.MusicEmbeds;
import kr.kro.backas.music.MusicPlayerClient;
import kr.kro.backas.music.MusicPlayerController;
import kr.kro.backas.util.MemberUtil;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.components.container.Container;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.entities.channel.middleman.MessageChannel;
import net.dv8tion.jda.api.interactions.InteractionHook;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;

public final class LyricsPresenter {

    private static final Logger LOGGER = LoggerFactory.getLogger(LyricsPresenter.class);
    private static final int EMBED_TEXT_LIMIT = 4000;
    private static final int MESSAGE_TEXT_BUDGET = 5800;
    private static final String TRUNCATED_NOTE = "… (이하 생략)";
    public static final String MACHINE_TRANSLATION_NOTE = "기계 번역한 가사입니다. 올바르지 않을 수 있습니다.";

    public static final String LLM_TRANSLATION_NOTE = "LLM으로 번역한 가사입니다. 올바르지 않을 수 있습니다.";

    public static String machineTranslationNote(@Nullable TranslationClient translator) {
        String note = translator != null && translator.isLlm() ? LLM_TRANSLATION_NOTE : MACHINE_TRANSLATION_NOTE;
        String status = translationStatus(translator);
        return status.isBlank() ? note : note + "\n" + status;
    }

    public static String translationStatus(@Nullable TranslationClient translator) {
        if (translator == null) return "";
        String model = translator.getModelName();
        if (model.isBlank()) return "";
        StringBuilder status = new StringBuilder("model: ").append(model);
        String compute = translator.getComputeLabel();
        if (!compute.isBlank()) status.append("\ncompute: ").append(compute);
        status.append("\nprocess: ").append(progressDisplay());
        int tokensPerSecond = translator.getTokensPerSecond();
        if (tokensPerSecond > 0) status.append('\n').append(tokensPerSecond).append(" token/s");
        return status.toString();
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

    private static volatile List<SongProgress> PROGRESS_PLAN = List.of();
    private static final Object PROGRESS_LOCK = new Object();

    private static void updateProgressPlan(@Nullable AudioTrack current, List<AudioTrack> targets) {
        synchronized (PROGRESS_LOCK) {
            Map<String, SongProgress> known = new ConcurrentHashMap<>();
            for (SongProgress entry : PROGRESS_PLAN) known.put(entry.id, entry);
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
            PROGRESS_PLAN = List.copyOf(plan);
        }
    }

    private static SongProgress progressEntry(AudioTrack track) {
        String id = track.getIdentifier();
        synchronized (PROGRESS_LOCK) {
            for (SongProgress entry : PROGRESS_PLAN) {
                if (entry.id.equals(id)) return entry;
            }
            SongProgress created = new SongProgress(id);
            List<SongProgress> plan = new ArrayList<>(PROGRESS_PLAN);
            plan.add(created);
            PROGRESS_PLAN = List.copyOf(plan);
            return created;
        }
    }

    static void reportSongJob(AudioTrack track, TranslationJobs.Job job) {
        progressEntry(track).job = job;
    }

    static void reportSongComplete(AudioTrack track) {
        progressEntry(track).complete = true;
    }

    private static String progressDisplay() {
        List<SongProgress> plan = PROGRESS_PLAN;
        if (plan.isEmpty()) return "번역 준비 중 ...";
        float sum = 0f;
        int completed = 0;
        SongProgress current = null;
        for (SongProgress entry : plan) {
            float fraction = entry.fraction();
            sum += fraction;
            if (fraction >= 1f) {
                completed++;
            } else if (current == null) {
                current = entry;
            }
        }
        java.text.DecimalFormat format = new java.text.DecimalFormat("0.##");
        String counter = " (total: " + format.format(100.0 * sum / plan.size()) + "%, " + completed + "/" + plan.size() + ")";
        if (current == null) return "100%" + counter;
        if (current.job == null) return "가사 불러오는 중 ..." + counter;
        return format.format(current.fraction() * 100.0) + "%" + counter;
    }

    private LyricsPresenter() {
    }

    public static void presentViaHook(MusicPlayerClient client, AudioTrack track, InteractionHook hook,
                                      long offsetMs, boolean liveWanted, @Nullable Member requester) {
        present(client, track, offsetMs, liveWanted, requester,
                embeds -> hook.editOriginalEmbeds(embeds).submit(),
                view -> hook.editOriginalComponents(view).useComponentsV2(true).submit());
    }

    public static void presentInChannel(MusicPlayerClient client, AudioTrack track, MessageChannel channel, long offsetMs) {
        present(client, track, offsetMs, true, null,
                embeds -> channel.sendMessageEmbeds(embeds).submit(),
                view -> channel.sendMessageComponents(view).useComponentsV2(true).submit());
    }

    private static void present(MusicPlayerClient client, AudioTrack track, long offsetMs, boolean liveWanted,
                                @Nullable Member requester,
                                Function<List<MessageEmbed>, CompletableFuture<Message>> sendEmbeds,
                                Function<Container, CompletableFuture<Message>> sendView) {
        MusicPlayerController controller = Main.getLuffia().getMusicPlayerController();
        CompletableFuture
                .supplyAsync(() -> {
                    try {
                        return controller.getLyricsClient().find(track.getInfo());
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                })
                .whenComplete((lyrics, error) -> {
                    if (error != null) {
                        Throwable cause = error.getCause() == null ? error : error.getCause();
                        LOGGER.warn("lyrics lookup failed for {}", track.getInfo().title, cause);
                        if (requester != null) {
                            sendEmbeds.apply(List.of(MusicEmbeds.error(requester, "가사를 불러오지 못했습니다", cause.getMessage()).build()));
                        }
                        return;
                    }
                    if (lyrics == null || lyrics.instrumental() || (!lyrics.hasPlain() && !lyrics.hasSynced())) {
                        if (requester != null) {
                            sendEmbeds.apply(List.of(MusicEmbeds.error(requester, "가사를 찾지 못했습니다",
                                    track.getInfo().author + " - " + track.getInfo().title).build()));
                        }
                        prefetchNext(client);
                        return;
                    }
                    if (!client.isCurrentTrack(track)) return;
                    if (liveWanted && lyrics.hasSynced()) {
                        client.stopLyrics("새 가사 표시로 대체되었습니다");
                        Container initial = LyricsSession.buildView(track, lyrics, -1, null, "가사 동기화 준비 중");
                        sendView.apply(initial).whenComplete((message, sendError) -> {
                            if (sendError != null || message == null) {
                                LOGGER.warn("failed to send lyrics message", sendError);
                                return;
                            }
                            if (!client.isCurrentTrack(track)) return;
                            LyricsSession session = new LyricsSession(client, track, lyrics, message,
                                    controller.getScheduler(), controller.getTranslationClient(), offsetMs);
                            client.setLyricsSession(session);
                            session.start();
                        });
                        return;
                    }
                    showFull(client, track, lyrics, requester, liveWanted, sendEmbeds, controller.getTranslationClient());
                });
    }

    private static void showFull(MusicPlayerClient client, AudioTrack track, Lyrics lyrics, @Nullable Member requester, boolean liveWanted,
                                 Function<List<MessageEmbed>, CompletableFuture<Message>> sendEmbeds,
                                 TranslationClient translator) {
        List<String> lines = fullLines(lyrics);
        String footer = liveWanted ? "타임스탬프 가사가 없어 전체 가사로 표시합니다" : SharedConstant.RELEASE_VERSION;
        if (requester != null) footer += " · 요청: " + MemberUtil.getName(requester);
        String finalFooter = footer;
        List<LyricLine> asLines = new ArrayList<>();
        for (String line : lines) asLines.add(new LyricLine(0, line));
        boolean willTranslate = translator != null && translator.isEnabled()
                && !LyricsLanguage.KOREAN.equals(LyricsLanguage.detect(asLines));
        if (translator != null && translator.isEnabled() && !willTranslate) reportSongComplete(track);
        String initialNote = willTranslate
                ? LyricsSession.TRANSLATING_NOTE + "\n" + translationStatus(translator)
                : null;
        sendEmbeds.apply(buildFullEmbeds(track, lines, null, finalFooter, initialNote)).whenComplete((message, sendError) -> {
            if (sendError != null || message == null) {
                LOGGER.warn("failed to send full lyrics", sendError);
                return;
            }
            if (!willTranslate) return;
            CompletableFuture.runAsync(() -> {
                prefetchNext(client);
                String cacheKey = TranslationJobs.cacheKey("plain", lines);
                Map<Integer, String> cache = translator.cacheFor(cacheKey);
                java.util.concurrent.atomic.AtomicReference<TranslationJobs.Job> jobRef = new java.util.concurrent.atomic.AtomicReference<>();
                ProgressiveEditor editor = new ProgressiveEditor(message.getChannel().getIdLong(), () -> {
                    TranslationJobs.Job current = jobRef.get();
                    boolean translating = current == null || !current.done().isDone();
                    String note = translating
                            ? LyricsSession.TRANSLATING_NOTE + "\n" + translationStatus(translator)
                            : (cache.isEmpty() ? "번역에 실패했습니다" : machineTranslationNote(translator));
                    message.editMessageEmbeds(buildFullEmbeds(track, lines, cache, finalFooter, note))
                            .queue(null, e -> LOGGER.debug("progressive lyrics edit failed", e));
                });
                TranslationJobs.Job job = TranslationJobs.submit(translator, cacheKey, lines, true,
                        (index, text) -> editor.requestEdit());
                jobRef.set(job);
                reportSongJob(track, job);
                ScheduledFuture<?> heartbeat = Main.getLuffia().getMusicPlayerController().getScheduler()
                        .scheduleAtFixedRate(editor::requestEdit, 3, 5, TimeUnit.SECONDS);
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
                String doneNote = translations.isEmpty()
                        ? "번역에 실패했습니다"
                        : machineTranslationNote(translator);
                message.editMessageEmbeds(buildFullEmbeds(track, lines, translations, finalFooter, doneNote))
                        .queue(null, e -> LOGGER.debug("failed to attach translations", e));
            });
        });
    }

    private static final class ProgressiveEditor {
        private static final long MIN_INTERVAL_MS = 2000;
        private final long channelId;
        private final Runnable edit;
        private final ScheduledExecutorService scheduler = Main.getLuffia().getMusicPlayerController().getScheduler();
        private long lastEditAt;
        private ScheduledFuture<?> pending;
        private boolean cancelled;

        ProgressiveEditor(long channelId, Runnable edit) {
            this.channelId = channelId;
            this.edit = edit;
        }

        synchronized void requestEdit() {
            if (cancelled || pending != null) return;
            long wait = Math.max(0, lastEditAt + MIN_INTERVAL_MS - System.currentTimeMillis());
            pending = scheduler.schedule(this::run, wait, TimeUnit.MILLISECONDS);
        }

        private void run() {
            synchronized (this) {
                pending = null;
                if (cancelled) return;
                if (!EditRateLimiter.tryAcquire(channelId)) {
                    long retry = Math.max(500, EditRateLimiter.millisUntilNext(channelId));
                    pending = scheduler.schedule(this::run, retry, TimeUnit.MILLISECONDS);
                    return;
                }
                lastEditAt = System.currentTimeMillis();
            }
            edit.run();
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

    public static void prefetchNext(MusicPlayerClient client) {
        if (!client.isAutoLyricsEnabled()) {
            LOGGER.info("lyrics prefetch skipped: auto lyrics disabled");
            return;
        }
        MusicPlayerController controller = Main.getLuffia().getMusicPlayerController();
        TranslationClient translator = controller.getTranslationClient();
        if (translator == null || !translator.isEnabled()) {
            LOGGER.info("lyrics prefetch skipped: translator disabled");
            return;
        }
        if (!PREFETCH_RUNNING.add(client)) {
            PREFETCH_RERUN.put(client, Boolean.TRUE);
            LOGGER.info("lyrics prefetch already running, will rerun when it finishes");
            return;
        }
        TranslationJobs.EXECUTOR.execute(() -> {
            try {
                do {
                    PREFETCH_RERUN.remove(client);
                    List<AudioTrack> queue = client.getTrackQueue();
                    int count = translator.isActivePrimary() ? PREFETCH_COUNT_FAST : PREFETCH_COUNT;
                    List<AudioTrack> targets = new ArrayList<>(queue.subList(0, Math.min(count, queue.size())));
                    updateProgressPlan(client.getCurrentPlaying(), targets);
                    LOGGER.info("lyrics prefetch pass over {} queued track(s)", targets.size());
                    for (AudioTrack next : targets) {
                        if (!client.isAutoLyricsEnabled()) return;
                        prefetchTrack(controller, translator, next);
                    }
                } while (PREFETCH_RERUN.remove(client) != null && client.isAutoLyricsEnabled());
            } finally {
                PREFETCH_RUNNING.remove(client);
            }
        });
    }

    private static void prefetchTrack(MusicPlayerController controller, TranslationClient translator, AudioTrack next) {
        String key = next.getIdentifier();
        if (!PREFETCHING.add(key)) return;
        try {
            LOGGER.info("prefetching lyrics translation for {}", next.getInfo().title);
            Lyrics lyrics = controller.getLyricsClient().find(next.getInfo());
            if (lyrics == null || lyrics.instrumental()) {
                LOGGER.info("no lyrics to prefetch for {}", next.getInfo().title);
                reportSongComplete(next);
                return;
            }
            if (lyrics.hasSynced()) {
                List<String> lines = new ArrayList<>();
                lyrics.synced().forEach(line -> lines.add(line.text()));
                if (LyricsLanguage.KOREAN.equals(LyricsLanguage.detect(lyrics.synced()))) {
                    reportSongComplete(next);
                    return;
                }
                TranslationJobs.Job job = TranslationJobs.submit(translator, TranslationJobs.cacheKey("synced", lines), lines, false, null);
                reportSongJob(next, job);
                job.done().join();
            } else if (lyrics.hasPlain()) {
                List<String> lines = fullLines(lyrics);
                List<LyricLine> asLines = new ArrayList<>();
                for (String line : lines) asLines.add(new LyricLine(0, line));
                if (LyricsLanguage.KOREAN.equals(LyricsLanguage.detect(asLines))) {
                    reportSongComplete(next);
                    return;
                }
                TranslationJobs.Job job = TranslationJobs.submit(translator, TranslationJobs.cacheKey("plain", lines), lines, false, null);
                reportSongJob(next, job);
                job.done().join();
            } else {
                reportSongComplete(next);
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

    private static List<MessageEmbed> buildFullEmbeds(AudioTrack track, List<String> lines,
                                                      @Nullable Map<Integer, String> translations, String footer,
                                                      @Nullable String note) {
        List<String> pages = paginate(lines, translations);
        if (note != null && !note.isBlank()) {
            StringBuilder small = new StringBuilder();
            for (String noteLine : note.split("\n")) small.append("\n-# ").append(noteLine);
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
                builder.setAuthor(track.getInfo().author + " - " + track.getInfo().title, track.getInfo().uri)
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
            String entry = lines.get(i);
            String translation = translations == null ? null : translations.get(i);
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
