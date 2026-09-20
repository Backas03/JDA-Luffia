package kr.kro.backas.music.lyrics;

import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import kr.kro.backas.Main;
import kr.kro.backas.SharedConstant;
import kr.kro.backas.music.MusicEmbeds;
import kr.kro.backas.music.MusicPlayerClient;
import kr.kro.backas.music.MusicPlayerController;
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
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Function;

public final class LyricsPresenter {

    private static final Logger LOGGER = LoggerFactory.getLogger(LyricsPresenter.class);
    private static final int EMBED_TEXT_LIMIT = 4000;
    private static final int MAX_FULL_PAGES = 3;

    private LyricsPresenter() {
    }

    public static void presentViaHook(MusicPlayerClient client, AudioTrack track, InteractionHook hook,
                                      long offsetMs, boolean liveWanted, @Nullable Member requester) {
        present(client, track, offsetMs, liveWanted, requester,
                embeds -> hook.editOriginalEmbeds(embeds).queue(),
                view -> hook.editOriginalComponents(view).useComponentsV2(true).submit());
    }

    public static void presentInChannel(MusicPlayerClient client, AudioTrack track, MessageChannel channel, long offsetMs) {
        present(client, track, offsetMs, true, null,
                embeds -> channel.sendMessageEmbeds(embeds).queue(),
                view -> channel.sendMessageComponents(view).useComponentsV2(true).submit());
    }

    private static void present(MusicPlayerClient client, AudioTrack track, long offsetMs, boolean liveWanted,
                                @Nullable Member requester,
                                Consumer<List<MessageEmbed>> sendMany,
                                Function<Container, CompletableFuture<Message>> sendOne) {
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
                            sendMany.accept(List.of(MusicEmbeds.error(requester, "가사를 불러오지 못했습니다", cause.getMessage()).build()));
                        }
                        return;
                    }
                    if (lyrics == null || lyrics.instrumental() || (!lyrics.hasPlain() && !lyrics.hasSynced())) {
                        if (requester != null) {
                            sendMany.accept(List.of(MusicEmbeds.error(requester, "가사를 찾지 못했습니다",
                                    track.getInfo().author + " - " + track.getInfo().title).build()));
                        }
                        return;
                    }
                    if (!client.isCurrentTrack(track)) return;
                    if (liveWanted && lyrics.hasSynced()) {
                        client.stopLyrics("새 가사 표시로 대체되었습니다");
                        Container initial = LyricsSession.buildView(track, lyrics, -1, "가사 동기화 준비 중");
                        sendOne.apply(initial).whenComplete((message, sendError) -> {
                            if (sendError != null || message == null) {
                                LOGGER.warn("failed to send lyrics message", sendError);
                                return;
                            }
                            if (!client.isCurrentTrack(track)) return;
                            LyricsSession session = new LyricsSession(client, track, lyrics, message,
                                    controller.getScheduler(), offsetMs);
                            client.setLyricsSession(session);
                            session.start();
                        });
                        return;
                    }
                    sendMany.accept(fullLyricsEmbeds(track, lyrics, requester, liveWanted));
                });
    }

    private static List<MessageEmbed> fullLyricsEmbeds(AudioTrack track, Lyrics lyrics, @Nullable Member requester, boolean liveWanted) {
        String text = lyrics.hasPlain() ? lyrics.plain() : joinSynced(lyrics);
        List<String> pages = paginate(text);
        List<MessageEmbed> embeds = new ArrayList<>();
        int shown = Math.min(pages.size(), MAX_FULL_PAGES);
        for (int i = 0; i < shown; i++) {
            EmbedBuilder builder = new EmbedBuilder()
                    .setColor(MusicEmbeds.PRIMARY)
                    .setDescription(pages.get(i));
            if (i == 0) {
                builder.setAuthor(track.getInfo().author + " - " + track.getInfo().title, track.getInfo().uri)
                        .setThumbnail(MusicEmbeds.thumbnailOf(track));
            }
            if (i == shown - 1) {
                String footer = liveWanted ? "타임스탬프 가사가 없어 전체 가사로 표시합니다" : SharedConstant.RELEASE_VERSION;
                if (requester != null) footer += " · 요청: " + kr.kro.backas.util.MemberUtil.getName(requester);
                builder.setFooter(footer);
            }
            embeds.add(builder.build());
        }
        return embeds;
    }

    private static String joinSynced(Lyrics lyrics) {
        StringBuilder sb = new StringBuilder();
        lyrics.synced().forEach(line -> sb.append(line.text()).append('\n'));
        return sb.toString();
    }

    private static List<String> paginate(String text) {
        List<String> pages = new ArrayList<>();
        StringBuilder page = new StringBuilder();
        for (String line : text.split("\\r?\\n")) {
            if (page.length() + line.length() + 1 > EMBED_TEXT_LIMIT) {
                pages.add(page.toString());
                page = new StringBuilder();
            }
            page.append(line).append('\n');
        }
        if (!page.isEmpty()) pages.add(page.toString());
        return pages;
    }
}
