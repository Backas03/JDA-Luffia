package kr.kro.backas.music;

import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import com.sedmelluq.discord.lavaplayer.track.AudioTrackInfo;
import kr.kro.backas.Main;
import kr.kro.backas.music.lyrics.EditRateLimiter;
import kr.kro.backas.util.DiscordSafe;
import kr.kro.backas.util.MemberUtil;
import net.dv8tion.jda.api.components.container.Container;
import net.dv8tion.jda.api.components.container.ContainerChildComponent;
import net.dv8tion.jda.api.components.section.Section;
import net.dv8tion.jda.api.components.separator.Separator;
import net.dv8tion.jda.api.components.textdisplay.TextDisplay;
import net.dv8tion.jda.api.components.thumbnail.Thumbnail;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.channel.middleman.MessageChannel;
import net.dv8tion.jda.api.interactions.InteractionHook;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

public final class TrackCard {

    private static final Logger LOGGER = LoggerFactory.getLogger(TrackCard.class);
    static final String LOOKING_UP = "가사를 찾고 있습니다";
    static final String PLAYING_NOTE = "음악을 재생합니다";
    private static final int MAX_AUTHOR_LENGTH = 80;
    private static final int MAX_NAME_LENGTH = 40;
    private static final long RECOLLAPSE_SECONDS = 3;

    private final MusicPlayerClient client;
    private final AudioTrack track;
    private final long channelId;
    private final CompletableFuture<Message> message;
    private final AtomicBoolean closed = new AtomicBoolean();

    private TrackCard(MusicPlayerClient client, AudioTrack track, long channelId, CompletableFuture<Message> message) {
        this.client = client;
        this.track = track;
        this.channelId = channelId;
        this.message = message;
    }

    public static TrackCard send(MusicPlayerClient client, AudioTrack track, MessageChannel channel, @Nullable InteractionHook hook) {
        Container initial = frame(track, client.getGuild(), List.of(TextDisplay.of("-# " + LOOKING_UP)));
        CompletableFuture<Message> sent = hook != null
                ? hook.editOriginalComponents(initial).useComponentsV2(true).submit()
                : channel.sendMessageComponents(initial).useComponentsV2(true).submit();
        TrackCard card = new TrackCard(client, track, channel.getIdLong(), sent);
        sent.whenComplete((message, error) -> {
            if (error != null) {
                LOGGER.warn("failed to send track card for {}", track.getInfo().title, error);
                return;
            }
            card.watch();
        });
        return card;
    }

    public AudioTrack track() {
        return track;
    }

    public long channelId() {
        return channelId;
    }

    public boolean isClosed() {
        return closed.get();
    }

    public Container frame(List<? extends ContainerChildComponent> body) {
        return frame(track, client.getGuild(), body);
    }

    public CompletableFuture<?> edit(Container view, long deadlineAt) {
        return message.thenCompose(current -> current.editMessageComponents(view).useComponentsV2(true).deadline(deadlineAt).submit());
    }

    public boolean close() {
        if (!closed.compareAndSet(false, true)) return false;
        collapse();
        scheduler().schedule(this::collapse, RECOLLAPSE_SECONDS, TimeUnit.SECONDS);
        return true;
    }

    private void collapse() {
        edit(frame(List.of()), System.currentTimeMillis() + EditRateLimiter.EDIT_DEADLINE_MS)
                .whenComplete((result, error) -> {
                    if (error != null) LOGGER.debug("failed to collapse track card for {}", track.getInfo().title, error);
                });
    }

    private void watch() {
        AtomicReference<ScheduledFuture<?>> watcher = new AtomicReference<>();
        watcher.set(scheduler().scheduleAtFixedRate(() -> {
            if (!closed.get() && client.isCurrentTrack(track)) return;
            ScheduledFuture<?> self = watcher.get();
            if (self != null) self.cancel(false);
            close();
        }, 1, 1, TimeUnit.SECONDS));
    }

    private static ScheduledExecutorService scheduler() {
        return Main.getLuffia().getMusicPlayerController().getScheduler();
    }

    public static Container frame(AudioTrack track, @Nullable Guild guild, List<? extends ContainerChildComponent> body) {
        List<ContainerChildComponent> children = new ArrayList<>();
        children.add(header(track, guild, !body.isEmpty()));
        if (!body.isEmpty()) {
            children.add(Separator.createDivider(Separator.Spacing.SMALL));
            children.addAll(body);
        }
        String requester = requesterLabel(track.getUserData(MusicSelection.class));
        if (requester != null) {
            children.add(Separator.createDivider(Separator.Spacing.SMALL));
            children.add(TextDisplay.of("-# " + requester));
        }
        return Container.of(children).withAccentColor(MusicEmbeds.PRIMARY);
    }

    public static ContainerChildComponent header(AudioTrack track, @Nullable Guild guild, boolean playing) {
        TextDisplay text = TextDisplay.of(headerText(track.getInfo(), MusicEmbeds.botName(guild), MusicEmbeds.sourceLabel(track), playing));
        String artwork = MusicEmbeds.thumbnailOf(track);
        return artwork == null ? text : Section.of(Thumbnail.fromUrl(artwork), text);
    }

    static String headerText(AudioTrackInfo info, String botName, String source, boolean playing) {
        StringBuilder text = new StringBuilder();
        if (info.author != null && !info.author.isBlank()) {
            text.append("**").append(DiscordSafe.escaped(info.author, MAX_AUTHOR_LENGTH)).append("**\n");
        }
        text.append("## ").append(MusicEmbeds.titleLink(info)).append('\n');
        if (playing) text.append(PLAYING_NOTE).append('\n');
        return text.append("**노래 봇** ").append(botName)
                .append(" · **재생 시간** ").append(MusicEmbeds.durationOf(info))
                .append(" · **출처** ").append(source)
                .toString();
    }

    @Nullable
    static String requesterLabel(@Nullable MusicSelection selection) {
        if (selection == null) return null;
        String name = DiscordSafe.escaped(MemberUtil.getName(selection.getRequestedMember()), MAX_NAME_LENGTH);
        if (selection.isAutoplay()) return "AI 자동 추천 · " + name;
        if (selection.isAiRecommended()) return "AI 추천 · " + name;
        return "요청 " + name;
    }
}
