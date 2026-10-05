package kr.kro.backas.music;

import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import com.sedmelluq.discord.lavaplayer.track.AudioTrackInfo;
import kr.kro.backas.Main;
import kr.kro.backas.SharedConstant;
import kr.kro.backas.music.ai.AiAutoplay;
import kr.kro.backas.music.lyrics.EditRateLimiter;
import kr.kro.backas.music.lyrics.TranslationJobs;
import kr.kro.backas.util.DiscordSafe;
import kr.kro.backas.util.DurationUtil;
import net.dv8tion.jda.api.components.container.Container;
import net.dv8tion.jda.api.components.container.ContainerChildComponent;
import net.dv8tion.jda.api.components.mediagallery.MediaGallery;
import net.dv8tion.jda.api.components.mediagallery.MediaGalleryItem;
import net.dv8tion.jda.api.components.section.Section;
import net.dv8tion.jda.api.components.separator.Separator;
import net.dv8tion.jda.api.components.textdisplay.TextDisplay;
import net.dv8tion.jda.api.components.thumbnail.Thumbnail;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.channel.middleman.MessageChannel;
import net.dv8tion.jda.api.exceptions.ErrorResponseException;
import net.dv8tion.jda.api.interactions.InteractionHook;
import net.dv8tion.jda.api.requests.ErrorResponse;
import net.dv8tion.jda.api.requests.restaction.MessageCreateAction;
import net.dv8tion.jda.api.requests.restaction.WebhookMessageEditAction;
import net.dv8tion.jda.api.utils.FileUpload;
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
    static final String PLAYED_NOTE = "재생 완료됨";
    private static final String BANNER_FILE = "banner.jpg";
    private static final int MAX_AUTHOR_LENGTH = 80;
    private static final long RECOLLAPSE_SECONDS = 3;

    private static final long BANNER_WAIT_MS = 3000;

    private final MusicPlayerClient client;
    private final AudioTrack track;
    private final long channelId;
    private final MessageChannel recordChannel;
    private final AtomicBoolean closed = new AtomicBoolean();
    private volatile CompletableFuture<Message> message = new CompletableFuture<>();
    private volatile boolean bannerAttached;
    private volatile List<? extends ContainerChildComponent> lastBody = List.of();
    private volatile long finishedAt;

    private TrackCard(MusicPlayerClient client, AudioTrack track, long channelId, @Nullable MessageChannel recordChannel) {
        this.client = client;
        this.track = track;
        this.channelId = channelId;
        this.recordChannel = recordChannel;
    }

    public static TrackCard send(MusicPlayerClient client, AudioTrack track, MessageChannel channel, @Nullable InteractionHook hook) {
        return send(client, track, channel, hook, null);
    }

    public static TrackCard sendMirror(MusicPlayerClient client, AudioTrack track, MessageChannel channel, @Nullable MessageChannel recordChannel) {
        return send(client, track, channel, null, recordChannel == null || recordChannel.getIdLong() == channel.getIdLong()
                ? null : recordChannel);
    }

    private static TrackCard send(MusicPlayerClient client, AudioTrack track, MessageChannel channel, @Nullable InteractionHook hook,
                                  @Nullable MessageChannel recordChannel) {
        List<TextDisplay> body = List.of(TextDisplay.of("-# " + LOOKING_UP));
        TrackCard card = new TrackCard(client, track, channel.getIdLong(), recordChannel);
        card.lastBody = body;
        String bannerUrl = MusicEmbeds.bannerOf(track);
        CompletableFuture<Message> sent = CompletableFuture
                .supplyAsync(() -> ArtworkColors.await(bannerUrl, BANNER_WAIT_MS), TranslationJobs.EXECUTOR)
                .thenCompose(banner -> {
                    boolean attach = banner != null && banner.isFile();
                    card.bannerAttached = attach;
                    Container initial = frame(client, track, body, 0, attach);
                    if (hook != null) {
                        WebhookMessageEditAction<Message> action = hook.editOriginalComponents(initial).useComponentsV2(true);
                        if (attach) action = action.setFiles(bannerFile(banner));
                        return action.submit();
                    }
                    MessageCreateAction action = channel.sendMessageComponents(initial).useComponentsV2(true);
                    if (attach) action = action.addFiles(bannerFile(banner));
                    return action.submit();
                });
        card.message = sent;
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
        lastBody = body;
        return frame(client, track, body, finishedAt, bannerAttached);
    }

    public static FileUpload bannerFile(ArtworkColors.Banner banner) {
        return FileUpload.fromData(banner.data(), BANNER_FILE);
    }

    public static ArtworkColors.Banner awaitBanner(AudioTrack track) {
        return ArtworkColors.await(MusicEmbeds.bannerOf(track), BANNER_WAIT_MS);
    }

    @Nullable
    public static MediaGallery banner(AudioTrack track, boolean bannerAttached) {
        ArtworkColors.Banner banner = ArtworkColors.bannerFor(MusicEmbeds.bannerOf(track));
        String bannerUrl = banner == null ? null : banner.url() != null ? banner.url() : bannerAttached ? "attachment://" + BANNER_FILE : null;
        return bannerUrl == null ? null : MediaGallery.of(MediaGalleryItem.fromUrl(bannerUrl));
    }

    public CompletableFuture<?> edit(Container view, long deadlineAt) {
        if (closed.get()) return CompletableFuture.completedFuture(null);
        return submitEdit(view, deadlineAt);
    }

    private CompletableFuture<?> submitEdit(Container view, long deadlineAt) {
        return message.thenCompose(current -> current.editMessageComponents(view).useComponentsV2(true)
                        .deadline(Math.max(deadlineAt, System.currentTimeMillis() + EditRateLimiter.EDIT_DEADLINE_MS)).submit())
                .whenComplete((result, error) -> {
                    if (error != null && isGone(error)) onGone();
                });
    }

    private void onGone() {
        if (!closed.compareAndSet(false, true)) return;
        LOGGER.info("track card for {} was deleted, leaving it alone until the next track", track.getInfo().title);
        client.onTrackCardGone(this);
    }

    public static boolean isGone(Throwable error) {
        for (Throwable current = error; current != null; current = current.getCause()) {
            if (current instanceof ErrorResponseException response && response.getErrorResponse() == ErrorResponse.UNKNOWN_MESSAGE) {
                return true;
            }
        }
        return false;
    }

    public void refresh() {
        if (closed.get()) return;
        List<? extends ContainerChildComponent> body = lastBody;
        if (body.isEmpty()) return;
        edit(frame(body), System.currentTimeMillis() + EditRateLimiter.EDIT_DEADLINE_MS)
                .whenComplete((result, error) -> {
                    if (error != null) LOGGER.debug("failed to refresh track card for {}", track.getInfo().title, error);
                });
    }

    @Nullable
    public MessageChannel recordChannel() {
        return recordChannel;
    }

    public void delete() {
        closed.set(true);
        remove();
    }

    private void remove() {
        message.thenAccept(current -> current.delete().queue(null,
                error -> LOGGER.debug("failed to delete track card for {}", track.getInfo().title, error)));
    }

    public boolean close() {
        if (!closed.compareAndSet(false, true)) return false;
        finishedAt = System.currentTimeMillis();
        if (recordChannel != null) {
            remove();
            ArtworkColors.Banner banner = ArtworkColors.bannerFor(MusicEmbeds.bannerOf(track));
            boolean attach = banner != null && banner.isFile();
            MessageCreateAction record = recordChannel.sendMessageComponents(frame(client, track, List.of(), finishedAt, attach))
                    .useComponentsV2(true);
            if (attach) record = record.addFiles(bannerFile(banner));
            record.queue(null, error -> LOGGER.debug("failed to leave a play record for {}", track.getInfo().title, error));
            return true;
        }
        collapse();
        scheduler().schedule(this::collapse, RECOLLAPSE_SECONDS, TimeUnit.SECONDS);
        return true;
    }

    private void collapse() {
        submitEdit(frame(List.of()), System.currentTimeMillis() + EditRateLimiter.EDIT_DEADLINE_MS)
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

    public static Container frame(MusicPlayerClient client, AudioTrack track, List<? extends ContainerChildComponent> body, long finishedAt,
                                  boolean bannerAttached) {
        boolean playing = !body.isEmpty();
        List<ContainerChildComponent> children = new ArrayList<>();
        MediaGallery banner = banner(track, bannerAttached);
        if (banner != null) {
            children.add(banner);
            children.add(headerText(client, track, playing));
        } else {
            children.add(header(client, track, playing));
        }
        if (playing) {
            children.add(Separator.createDivider(Separator.Spacing.SMALL));
            children.addAll(body);
        }
        children.add(Separator.createDivider(Separator.Spacing.SMALL));
        children.add(TextDisplay.of(footerText(MusicEmbeds.botName(client.getGuild()), playing ? 0 : finishedAt)));
        return Container.of(children).withAccentColor(ArtworkColors.of(MusicEmbeds.thumbnailOf(track)));
    }

    public static ContainerChildComponent header(MusicPlayerClient client, AudioTrack track, boolean playing) {
        TextDisplay text = headerText(client, track, playing);
        String artwork = MusicEmbeds.thumbnailOf(track);
        return artwork == null ? text : Section.of(Thumbnail.fromUrl(artwork), text);
    }

    private static TextDisplay headerText(MusicPlayerClient client, AudioTrack track, boolean playing) {
        return TextDisplay.of(headerText(track.getInfo(), MusicEmbeds.sourceLabel(track), playing ? settingsLine(client) : null));
    }

    static String headerText(AudioTrackInfo info, String source, @Nullable String settings) {
        StringBuilder text = new StringBuilder("### ").append(MusicEmbeds.titleLink(info)).append('\n');
        if (info.author != null && !info.author.isBlank()) {
            text.append("-# ").append(DiscordSafe.escaped(info.author, MAX_AUTHOR_LENGTH)).append('\n');
        }
        text.append("-# ").append(info.isStream ? "라이브" : DurationUtil.formatClock(info.length / 1000)).append(" · ").append(source);
        if (settings != null) text.append("\n-# ").append(settings);
        return text.toString();
    }

    static String footerText(String botName, long finishedAt) {
        String first = finishedAt > 0 ? botName + " <t:" + finishedAt / 1000 + ":t> " + PLAYED_NOTE : botName;
        return "-# " + first + "\n-# " + SharedConstant.RELEASE_VERSION;
    }

    public static String settingsLine(MusicPlayerClient client) {
        List<String> settings = new ArrayList<>();
        settings.add("볼륨 " + client.getVolume() + "%");
        settings.add(client.getRepeatModeName());
        if (Math.abs(client.getCurrentPlaySpeed() - 1.0) > 0.001) settings.add(client.getCurrentPlaySpeed() + "배속");
        if (client.getKaraokeMode().isActive()) settings.add("노래방 " + client.getKaraokeMode().getName());
        if (!client.getCurrentEqualizer().isFlat()) settings.add("이퀄라이저 " + client.getCurrentEqualizer().getName());
        settings.add("AI 추천 " + autoplayStatus(client));
        return String.join(" · ", settings);
    }

    public static String autoplayStatus(MusicPlayerClient client) {
        AiAutoplay autoplay = client.getAutoplay();
        if (!autoplay.isEnabled()) return "꺼짐";
        if (!Main.getLuffia().getMusicPlayerController().getTranslationClient().isAvailable()) return "켜짐 (AI 서버 연결 안 됨)";
        String criteria = autoplay.getCriteria();
        return criteria.isBlank() ? "켜짐" : "켜짐 (" + criteria + ")";
    }
}
