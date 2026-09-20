package kr.kro.backas.music;

import com.github.topi314.lavasrc.ExtendedAudioPlaylist;
import com.sedmelluq.discord.lavaplayer.source.AudioSourceManager;
import com.sedmelluq.discord.lavaplayer.track.AudioPlaylist;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import com.sedmelluq.discord.lavaplayer.track.AudioTrackInfo;
import kr.kro.backas.SharedConstant;
import kr.kro.backas.music.service.youtube.YoutubeService;
import kr.kro.backas.util.DurationUtil;
import kr.kro.backas.util.MemberUtil;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.MessageEmbed;
import org.jetbrains.annotations.Nullable;

import java.awt.*;
import java.util.ArrayList;
import java.util.List;

public final class MusicEmbeds {
    public static final Color PRIMARY = Color.decode("#5e71ef");
    public static final Color ERROR = Color.decode("#f1554a");
    public static final Color SUCCESS = Color.decode("#57f287");

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

    public static String botName(JDA musicBot) {
        Member self = MemberUtil.getMember(musicBot.getSelfUser().getIdLong());
        return self != null ? MemberUtil.getName(self) : musicBot.getSelfUser().getName();
    }

    private static EmbedBuilder trackBase(AudioTrack track, JDA musicBot) {
        MusicSelection selection = track.getUserData(MusicSelection.class);
        AudioTrackInfo info = track.getInfo();
        EmbedBuilder builder = new EmbedBuilder()
                .setColor(PRIMARY)
                .setTitle(info.title, info.uri)
                .setThumbnail(thumbnailOf(track));
        if (info.author != null && !info.author.isBlank()) {
            builder.setAuthor(info.author);
        }
        builder.addField("노래 봇", botName(musicBot), true)
                .addField("재생 시간", durationOf(info), true)
                .addField("출처", sourceLabel(track), true);
        if (selection != null) {
            builder.setFooter(MemberUtil.getName(selection.getRequestedMember()));
        }
        return builder;
    }

    public static EmbedBuilder play(AudioTrack track, JDA musicBot) {
        return trackBase(track, musicBot).setDescription("음악을 재생합니다");
    }

    public static EmbedBuilder enqueue(AudioTrack track, JDA musicBot, int position) {
        return trackBase(track, musicBot)
                .setDescription("해당 음악이 대기열 " + position + "번째에 추가되었습니다");
    }

    public static EmbedBuilder playlistEnqueued(AudioPlaylist playlist,
                                                List<AudioTrack> added,
                                                int total,
                                                boolean startedPlaying,
                                                JDA musicBot,
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

        StringBuilder description = new StringBuilder();
        description.append(added.size()).append(startedPlaying ? "곡을 대기열에 추가 & 재생합니다" : "곡을 대기열에 추가했습니다");
        if (total > added.size()) {
            description.append("\n(전체 ").append(total).append("곡 중 최대 ")
                    .append(added.size()).append("곡까지만 추가됩니다)");
        }

        EmbedBuilder builder = new EmbedBuilder()
                .setColor(PRIMARY)
                .setTitle(playlist.getName(), url)
                .setThumbnail(artwork)
                .setDescription(description)
                .addField("노래 봇", botName(musicBot), true)
                .addField("총 재생 시간", DurationUtil.formatDuration((int) (totalMs / 1000)), true)
                .addField("출처", sourceLabel(first), true)
                .addField("첫 곡", "[" + first.getInfo().title + "](" + first.getInfo().uri + ")", false)
                .setFooter(MemberUtil.getName(requester));
        if (author != null && !author.isBlank()) {
            builder.setAuthor(author);
        }
        return builder;
    }

    public static EmbedBuilder trackFailed(AudioTrack track, JDA musicBot, @Nullable String reason) {
        AudioTrackInfo info = track.getInfo();
        EmbedBuilder builder = new EmbedBuilder()
                .setColor(ERROR)
                .setTitle("재생에 실패했습니다", info.uri)
                .setDescription(info.title + "\n다음 곡으로 넘어갑니다.")
                .addField("노래 봇", botName(musicBot), true)
                .addField("출처", sourceLabel(track), true);
        if (reason != null && !reason.isBlank()) {
            builder.addField("사유", reason.length() > 1000 ? reason.substring(0, 1000) : reason, false);
        }
        return builder;
    }

    public static String previewLinkOf(AudioTrack track) {
        AudioSourceManager source = track.getSourceManager();
        if (source != null && "spotify".equals(source.getSourceName()) && track.getInfo().uri != null) {
            return track.getInfo().uri;
        }
        return "";
    }

    public static String previewLinkOf(AudioPlaylist playlist, AudioTrack firstTrack) {
        if (playlist instanceof ExtendedAudioPlaylist extended && extended.getUrl() != null
                && !previewLinkOf(firstTrack).isEmpty()) {
            return extended.getUrl();
        }
        return "";
    }

    public static String toPlainText(MessageEmbed embed, String link) {
        StringBuilder text = new StringBuilder();
        if (embed.getAuthor() != null && embed.getAuthor().getName() != null) {
            text.append(embed.getAuthor().getName()).append('\n');
        }
        if (embed.getTitle() != null) {
            text.append("**").append(embed.getTitle()).append("**").append('\n');
        }
        if (embed.getDescription() != null) {
            text.append(embed.getDescription()).append('\n');
        }
        List<String> fields = new ArrayList<>();
        for (MessageEmbed.Field field : embed.getFields()) {
            if (field.getName() == null || field.getName().isBlank()) continue;
            String value = field.getValue() == null ? "" : field.getValue().replaceAll("\\[([^\\]]+)\\]\\([^)]+\\)", "$1");
            fields.add(field.getName() + ": " + value);
        }
        if (!fields.isEmpty()) {
            text.append(String.join(" | ", fields)).append('\n');
        }
        if (embed.getFooter() != null && embed.getFooter().getText() != null) {
            text.append("요청: ").append(embed.getFooter().getText()).append('\n');
        }
        text.append(link);
        return text.toString();
    }

    public static EmbedBuilder error(@Nullable Member member, String title, @Nullable String description) {
        EmbedBuilder builder = new EmbedBuilder()
                .setColor(ERROR)
                .setTitle(title)
                .setFooter(SharedConstant.RELEASE_VERSION);
        if (member != null) builder.setAuthor(MemberUtil.getName(member));
        if (description != null) builder.setDescription(description);
        return builder;
    }
}
