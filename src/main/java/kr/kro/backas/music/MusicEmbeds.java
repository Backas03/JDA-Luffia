package kr.kro.backas.music;

import kr.kro.backas.BuildInfo;
import com.github.topi314.lavasrc.ExtendedAudioPlaylist;
import com.sedmelluq.discord.lavaplayer.source.AudioSourceManager;
import com.sedmelluq.discord.lavaplayer.track.AudioPlaylist;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import com.sedmelluq.discord.lavaplayer.track.AudioTrackInfo;
import kr.kro.backas.music.service.youtube.YoutubeService;
import kr.kro.backas.util.DiscordSafe;
import kr.kro.backas.util.DurationUtil;
import kr.kro.backas.util.MemberUtil;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.components.container.Container;
import net.dv8tion.jda.api.components.container.ContainerChildComponent;
import net.dv8tion.jda.api.components.section.Section;
import net.dv8tion.jda.api.components.separator.Separator;
import net.dv8tion.jda.api.components.textdisplay.TextDisplay;
import net.dv8tion.jda.api.components.thumbnail.Thumbnail;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import org.jetbrains.annotations.Nullable;

import java.awt.*;
import java.util.List;

public final class MusicEmbeds {
    public static final Color PRIMARY = Color.decode("#5e71ef");
    public static final Color ERROR = Color.decode("#f1554a");
    public static final Color SUCCESS = Color.decode("#57f287");
    private static final int MAX_PREVIEW_TITLE = 100;
    private static final int MAX_LINK_TITLE = 80;

    private MusicEmbeds() {
    }

    @Nullable
    public static String thumbnailOf(AudioTrack track) {
        AudioTrackInfo info = track.getInfo();
        if (info.artworkUrl != null && !info.artworkUrl.isBlank()) {
            return info.artworkUrl;
        }
        return YoutubeService.getThumbnailURL(info.uri);
    }

    @Nullable
    public static String bannerOf(AudioTrack track) {
        String youtube = YoutubeService.getBannerURL(track.getInfo().uri);
        return youtube != null ? youtube : thumbnailOf(track);
    }

    public static String sourceLabel(AudioTrack track) {
        AudioSourceManager source = track.getSourceManager();
        String name = source == null ? null : source.getSourceName();
        if (name == null) return "알 수 없음";
        return switch (name) {
            case "youtube" -> "YouTube";
            case "spotify" -> "Spotify";
            default -> name;
        };
    }

    public static String durationOf(AudioTrackInfo info) {
        if (info.isStream) return "실시간 스트리밍";
        return DurationUtil.formatDuration((int) (info.length / 1000));
    }

    public static String botName(@Nullable Guild guild) {
        return guild == null ? "노래봇" : MemberUtil.getName(guild.getSelfMember());
    }

    public static String titleLink(AudioTrackInfo info) {
        return link(info.title, info.uri);
    }

    private static String link(String title, @Nullable String url) {
        String text = DiscordSafe.escaped(title, MAX_LINK_TITLE).replace('[', '(').replace(']', ')');
        if (url == null || url.isBlank()) return text;
        return "[" + text + "](" + url + ")";
    }

    public static Container playCard(AudioTrack track, Guild guild) {
        return trackCard(track, guild, "음악을 재생합니다");
    }

    public static Container enqueueCard(AudioTrack track, Guild guild, int position) {
        return trackCard(track, guild, "대기열 " + position + "번째에 추가했습니다");
    }

    private static Container trackCard(AudioTrack track, Guild guild, String status) {
        MusicSelection selection = track.getUserData(MusicSelection.class);
        String requester = null;
        if (selection != null) {
            String prefix = selection.isAutoplay() ? "AI 자동 추천 · " : selection.isAiRecommended() ? "AI 추천 · " : "";
            requester = prefix + MemberUtil.getName(selection.getRequestedMember());
        }
        String artwork = thumbnailOf(track);
        TextDisplay header = TextDisplay.of(TrackCard.headerText(track.getInfo(), sourceLabel(track), null) + "\n" + status);
        return Container.of(withArtwork(header, artwork), Separator.createDivider(Separator.Spacing.SMALL), footer(guild, requester))
                .withAccentColor(ArtworkColors.of(artwork));
    }

    public static Container playlistCard(AudioPlaylist playlist,
                                         List<AudioTrack> added,
                                         int total,
                                         boolean startedPlaying,
                                         Guild guild,
                                         Member requester) {
        AudioTrack first = added.get(0);
        long totalMs = added.stream().mapToLong(t -> t.getInfo().length).sum();
        String url = null;
        String artwork = null;
        String author = null;
        if (playlist instanceof ExtendedAudioPlaylist extended) {
            url = extended.getUrl();
            artwork = extended.getArtworkURL();
            author = extended.getAuthor();
        }
        if (artwork == null) artwork = thumbnailOf(first);
        String header = playlistText(playlist.getName(), url, author, totalMs, sourceLabel(first), added.size(), total, startedPlaying);
        return Container.of(withArtwork(TextDisplay.of(header), artwork),
                        Separator.createDivider(Separator.Spacing.SMALL),
                        TextDisplay.of(firstTrackText(first.getInfo())),
                        Separator.createDivider(Separator.Spacing.SMALL),
                        footer(guild, MemberUtil.getName(requester)))
                .withAccentColor(ArtworkColors.of(artwork));
    }

    static String playlistText(String name, @Nullable String url, @Nullable String author, long totalMs, String source,
                               int added, int total, boolean startedPlaying) {
        StringBuilder text = new StringBuilder("### ").append(link(name, url)).append('\n');
        if (author != null && !author.isBlank()) text.append("-# ").append(DiscordSafe.escaped(author, MAX_LINK_TITLE)).append('\n');
        text.append("-# 총 ").append(DurationUtil.formatDuration((int) (totalMs / 1000))).append(" · ").append(source).append('\n');
        text.append(added).append(startedPlaying ? "곡을 대기열에 추가 & 재생합니다" : "곡을 대기열에 추가했습니다");
        if (total > added) text.append("\n-# 전체 ").append(total).append("곡 중 ").append(added).append("곡이 추가되었습니다");
        return text.toString();
    }

    static String firstTrackText(AudioTrackInfo info) {
        StringBuilder text = new StringBuilder("**첫 곡** ").append(titleLink(info)).append("\n-# ");
        if (info.author != null && !info.author.isBlank()) text.append(DiscordSafe.escaped(info.author, MAX_LINK_TITLE)).append(" · ");
        return text.append(info.isStream ? "라이브" : DurationUtil.formatClock(info.length / 1000)).toString();
    }

    static String footerText(String botName, @Nullable String requester) {
        String first = requester == null ? botName : botName + " · " + DiscordSafe.escaped(requester, MAX_LINK_TITLE);
        return "-# " + first + "\n-# " + BuildInfo.VERSION;
    }

    private static ContainerChildComponent withArtwork(TextDisplay text, @Nullable String artwork) {
        return artwork == null || artwork.isBlank() ? text : Section.of(Thumbnail.fromUrl(artwork), text);
    }

    private static TextDisplay footer(Guild guild, @Nullable String requester) {
        return TextDisplay.of(footerText(botName(guild), requester));
    }

    public static EmbedBuilder trackFailed(AudioTrack track, Guild guild, @Nullable String reason) {
        AudioTrackInfo info = track.getInfo();
        EmbedBuilder builder = new EmbedBuilder()
                .setColor(ERROR)
                .setTitle("재생에 실패했습니다", info.uri)
                .setDescription(info.title + "\n다음 곡으로 넘어갑니다.")
                .addField("노래 봇", botName(guild), true)
                .addField("출처", sourceLabel(track), true);
        if (reason != null && !reason.isBlank()) {
            builder.addField("사유", reason.length() > 1000 ? reason.substring(0, 1000) : reason, false);
        }
        return builder;
    }

    public static String queuePreview(List<AudioTrack> queue, int rows) {
        StringBuilder preview = new StringBuilder();
        int shown = Math.min(queue.size(), rows);
        for (int i = 0; i < shown; i++) {
            AudioTrackInfo info = queue.get(i).getInfo();
            String title = DiscordSafe.escaped(info.title, MAX_PREVIEW_TITLE).replace('[', '(').replace(']', ')');
            preview.append(i + 1).append(". [").append(title).append("](").append(info.uri).append(")\n");
        }
        if (queue.size() > shown) {
            preview.append("... 외 ").append(queue.size() - shown).append("곡");
        }
        return preview.toString();
    }

    public static EmbedBuilder error(@Nullable Member member, String title, @Nullable String description) {
        EmbedBuilder builder = new EmbedBuilder()
                .setColor(ERROR)
                .setTitle(title)
                .setFooter(BuildInfo.VERSION);
        if (member != null) builder.setAuthor(MemberUtil.getName(member));
        if (description != null) builder.setDescription(description);
        return builder;
    }
}
