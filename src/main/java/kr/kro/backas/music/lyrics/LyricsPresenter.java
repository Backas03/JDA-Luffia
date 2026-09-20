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
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

public final class LyricsPresenter {

    private static final Logger LOGGER = LoggerFactory.getLogger(LyricsPresenter.class);
    private static final int EMBED_TEXT_LIMIT = 4000;
    private static final int MESSAGE_TEXT_BUDGET = 5800;
    private static final int TRANSLATION_BATCH = 50;
    private static final String TRUNCATED_NOTE = "… (이하 생략)";

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
                    showFull(track, lyrics, requester, liveWanted, sendEmbeds, controller.getTranslationClient());
                });
    }

    private static void showFull(AudioTrack track, Lyrics lyrics, @Nullable Member requester, boolean liveWanted,
                                 Function<List<MessageEmbed>, CompletableFuture<Message>> sendEmbeds,
                                 TranslationClient translator) {
        List<String> lines = fullLines(lyrics);
        String footer = liveWanted ? "타임스탬프 가사가 없어 전체 가사로 표시합니다" : SharedConstant.RELEASE_VERSION;
        if (requester != null) footer += " · 요청: " + MemberUtil.getName(requester);
        String finalFooter = footer;
        sendEmbeds.apply(buildFullEmbeds(track, lines, null, finalFooter)).whenComplete((message, sendError) -> {
            if (sendError != null || message == null) {
                LOGGER.warn("failed to send full lyrics", sendError);
                return;
            }
            if (translator == null || !translator.isEnabled()) return;
            List<LyricLine> asLines = new ArrayList<>();
            for (String line : lines) asLines.add(new LyricLine(0, line));
            if (LyricsLanguage.KOREAN.equals(LyricsLanguage.detect(asLines))) return;
            CompletableFuture.runAsync(() -> {
                Map<Integer, String> translations = translateAll(translator, track.getIdentifier() + ":plain", lines);
                if (translations.isEmpty()) return;
                message.editMessageEmbeds(buildFullEmbeds(track, lines, translations, finalFooter))
                        .queue(null, e -> LOGGER.debug("failed to attach translations", e));
            });
        });
    }

    private static Map<Integer, String> translateAll(TranslationClient translator, String cacheKey, List<String> lines) {
        Map<Integer, String> cache = translator.cacheFor(cacheKey);
        List<Integer> pending = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            if (!cache.containsKey(i) && LyricsLanguage.needsTranslation(lines.get(i))) pending.add(i);
        }
        for (int from = 0; from < pending.size(); from += TRANSLATION_BATCH) {
            List<Integer> indices = pending.subList(from, Math.min(pending.size(), from + TRANSLATION_BATCH));
            List<String> sources = new ArrayList<>(indices.size());
            for (int index : indices) sources.add(lines.get(index));
            try {
                List<String> translated = translator.translate("auto", sources);
                for (int i = 0; i < indices.size(); i++) {
                    String text = translated.get(i);
                    if (text != null && !text.isBlank() && !text.equals(sources.get(i))) cache.put(indices.get(i), text);
                }
            } catch (Exception e) {
                LOGGER.warn("full lyrics translation failed ({} lines)", indices.size(), e);
                break;
            }
        }
        return cache;
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
                entry += "\n*" + translation + "*";
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
