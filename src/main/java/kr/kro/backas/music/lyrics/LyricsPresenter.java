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
        String model = translator == null ? "" : translator.getModelName();
        String note = translator != null && translator.isLlm() ? LLM_TRANSLATION_NOTE : MACHINE_TRANSLATION_NOTE;
        return model.isBlank() ? note : note + "\n(model: " + model + ")";
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
        String initialFooter = willTranslate
                ? finalFooter + "\n" + LyricsSession.TRANSLATING_NOTE + "\n(model: " + translator.getModelName() + ")"
                : finalFooter;
        sendEmbeds.apply(buildFullEmbeds(track, lines, null, initialFooter)).whenComplete((message, sendError) -> {
            if (sendError != null || message == null) {
                LOGGER.warn("failed to send full lyrics", sendError);
                return;
            }
            if (!willTranslate) return;
            CompletableFuture.runAsync(() -> {
                String cacheKey = TranslationJobs.cacheKey("plain", lines);
                Map<Integer, String> cache = translator.cacheFor(cacheKey);
                ProgressiveEditor editor = new ProgressiveEditor(message.getChannel().getIdLong(), () ->
                        message.editMessageEmbeds(buildFullEmbeds(track, lines, cache, initialFooter))
                                .queue(null, e -> LOGGER.debug("progressive lyrics edit failed", e)));
                TranslationJobs.Job job = TranslationJobs.submit(translator, cacheKey, lines, true,
                        (index, text) -> editor.requestEdit());
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
                editor.cancel();
                if (job.isCancelled()) return;
                String doneFooter = translations.isEmpty()
                        ? finalFooter + "\n번역에 실패했습니다"
                        : finalFooter + "\n" + machineTranslationNote(translator);
                message.editMessageEmbeds(buildFullEmbeds(track, lines, translations, doneFooter))
                        .queue(null, e -> LOGGER.debug("failed to attach translations", e));
                prefetchNext(client);
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

    public static void prefetchNext(MusicPlayerClient client) {
        if (!client.isAutoLyricsEnabled()) return;
        List<AudioTrack> queue = client.getTrackQueue();
        if (queue.isEmpty()) return;
        MusicPlayerController controller = Main.getLuffia().getMusicPlayerController();
        TranslationClient translator = controller.getTranslationClient();
        if (translator == null || !translator.isEnabled()) return;
        List<AudioTrack> targets = new ArrayList<>(queue.subList(0, Math.min(PREFETCH_COUNT, queue.size())));
        CompletableFuture.runAsync(() -> {
            for (AudioTrack next : targets) {
                if (!client.isAutoLyricsEnabled()) return;
                prefetchTrack(controller, translator, next);
            }
        });
    }

    private static void prefetchTrack(MusicPlayerController controller, TranslationClient translator, AudioTrack next) {
        String key = next.getIdentifier();
        if (!PREFETCHING.add(key)) return;
        try {
            LOGGER.info("prefetching lyrics translation for {}", next.getInfo().title);
            Lyrics lyrics = controller.getLyricsClient().find(next.getInfo());
            if (lyrics == null || lyrics.instrumental()) return;
            if (lyrics.hasSynced()) {
                List<String> lines = new ArrayList<>();
                lyrics.synced().forEach(line -> lines.add(line.text()));
                if (LyricsLanguage.KOREAN.equals(LyricsLanguage.detect(lyrics.synced()))) return;
                TranslationJobs.submit(translator, TranslationJobs.cacheKey("synced", lines), lines, false, null).done().join();
            } else if (lyrics.hasPlain()) {
                List<String> lines = fullLines(lyrics);
                List<LyricLine> asLines = new ArrayList<>();
                for (String line : lines) asLines.add(new LyricLine(0, line));
                if (LyricsLanguage.KOREAN.equals(LyricsLanguage.detect(asLines))) return;
                TranslationJobs.submit(translator, TranslationJobs.cacheKey("plain", lines), lines, false, null).done().join();
            }
            LOGGER.info("prefetched lyrics translation for {}", next.getInfo().title);
        } catch (Exception e) {
            LOGGER.debug("lyrics prefetch failed for {}", next.getInfo().title, e);
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
                                                      @Nullable Map<Integer, String> translations, String footer) {
        List<String> pages = paginate(lines, translations);
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
