package kr.kro.backas.command.music.slash;

import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import com.sedmelluq.discord.lavaplayer.track.AudioTrackInfo;
import kr.kro.backas.Main;
import kr.kro.backas.SharedConstant;
import kr.kro.backas.command.api.SlashCommandSource;
import kr.kro.backas.music.MusicEmbeds;
import kr.kro.backas.music.MusicPlayerClient;
import kr.kro.backas.music.MusicPlayerController;
import kr.kro.backas.music.MusicSelection;
import kr.kro.backas.music.TrackCard;
import kr.kro.backas.music.ai.AiAutoplay;
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
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.channel.concrete.VoiceChannel;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.interactions.commands.build.SlashCommandData;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class QueueSlashCommand implements SlashCommandSource {
    public static final String COMMAND_NAME = "대기열";
    static final boolean USE_COMPONENTS_V2 = true;
    static final boolean ROW_THUMBNAILS = false;
    private static final int MAX_QUEUE_ROWS = 5;
    static final int MAX_VIEW_ROWS = 10;
    static final int MAX_THUMBNAIL_ROWS = 5;
    static final int MAX_AUTO_ROWS = AiAutoplay.AUTO_QUEUE_SIZE;
    private static final int MAX_TITLE_LENGTH = 80;
    private static final int MAX_NAME_LENGTH = 40;

    @Override
    public SlashCommandData buildCommand() {
        return Commands.slash(COMMAND_NAME, getDescription());
    }

    @Override
    public void onTriggered(SlashCommandInteractionEvent event) {
        Member member = event.getMember();
        VoiceChannel voiceChannel = MemberUtil.getJoinedVoiceChannel(member);
        if (voiceChannel == null) {
            event.replyEmbeds(MusicEmbeds.error(member,
                    "대기열 정보를 확인할 수 없습니다.",
                    "대기열을 확인하려면 음성채팅방에 먼저 참여해주세요.").build()).queue();
            return;
        }
        MusicPlayerController controller = Main.getLuffia().getMusicPlayerController();
        MusicPlayerClient client = controller.findFromVoiceChannel(voiceChannel);
        if (client == null) {
            event.replyEmbeds(MusicEmbeds.error(member,
                    "해당 채널에서 음악을 재생중인 노래 봇이 없습니다", null).build()).queue();
            return;
        }
        AudioTrack currentPlaying = client.getCurrentPlaying();
        if (currentPlaying == null) {
            event.replyEmbeds(MusicEmbeds.error(member, "현재 재생 대기 목록이 비어있습니다.", null)
                    .addField("노래 봇", MusicEmbeds.botName(client.getGuild()), false)
                    .build()).queue();
            return;
        }
        if (USE_COMPONENTS_V2) {
            event.replyComponents(buildView(client, currentPlaying)).useComponentsV2(true).queue();
        } else {
            event.replyEmbeds(buildEmbed(client, currentPlaying).build()).queue();
        }
    }

    private static Container buildView(MusicPlayerClient client, AudioTrack current) {
        AudioTrackInfo info = current.getInfo();
        MusicSelection selection = current.getUserData(MusicSelection.class);
        StringBuilder head = new StringBuilder("### ").append(link(info)).append('\n');
        if (info.author != null && !info.author.isBlank()) {
            head.append(DiscordSafe.escaped(info.author, MAX_TITLE_LENGTH)).append('\n');
        }
        head.append("-# ").append(progress(client, info)).append('\n');
        List<String> meta = new ArrayList<>();
        meta.add(MusicEmbeds.sourceLabel(current));
        if (selection != null) meta.add(selection.isAiRecommended() ? requesterOf(selection) : "요청 " + requesterOf(selection));
        head.append("-# ").append(String.join(" · ", meta));
        TextDisplay nowPlaying = TextDisplay.of(head.toString());
        String artwork = MusicEmbeds.thumbnailOf(current);

        List<ContainerChildComponent> children = new ArrayList<>();
        children.add(artwork == null ? nowPlaying : Section.of(Thumbnail.fromUrl(artwork), nowPlaying));
        children.add(TextDisplay.of("-# " + TrackCard.settingsLine(client)));
        children.add(Separator.createDivider(Separator.Spacing.SMALL));

        List<AudioTrack> queue = client.getTrackQueue();
        addList(children, "대기열", null, queue, MAX_VIEW_ROWS);

        List<AudioTrack> autoQueue = client.getAutoQueue();
        if (!autoQueue.isEmpty()) {
            children.add(Separator.createDivider(Separator.Spacing.SMALL));
            addList(children, "다음 추천", "대기열이 비면 이어서 재생합니다", autoQueue, MAX_AUTO_ROWS);
        }
        children.add(Separator.createDivider(Separator.Spacing.SMALL));
        children.add(TextDisplay.of("-# " + MusicEmbeds.botName(client.getGuild()) + " · " + SharedConstant.RELEASE_VERSION));
        return Container.of(children).withAccentColor(MusicEmbeds.PRIMARY);
    }

    private static void addList(List<ContainerChildComponent> children, String title, @Nullable String note,
                                List<AudioTrack> tracks, int limit) {
        int max = ROW_THUMBNAILS ? Math.min(limit, MAX_THUMBNAIL_ROWS) : limit;
        int shown = Math.min(tracks.size(), max);
        List<AudioTrack> visible = tracks.subList(0, shown);
        Set<String> requesters = requesters(visible);
        String single = requesters.size() == 1 && !tracks.isEmpty() ? requesters.iterator().next() : null;
        children.add(TextDisplay.of(heading(title, tracks.size(), totalLength(tracks), single, note)));
        if (tracks.isEmpty()) return;

        StringBuilder text = new StringBuilder();
        for (int i = 0; i < shown; i++) {
            AudioTrack track = tracks.get(i);
            AudioTrackInfo info = track.getInfo();
            MusicSelection selection = track.getUserData(MusicSelection.class);
            List<String> details = new ArrayList<>();
            if (info.author != null && !info.author.isBlank()) details.add(DiscordSafe.escaped(info.author, MAX_NAME_LENGTH));
            details.add(info.isStream ? "라이브" : DurationUtil.formatClock(info.length / 1000));
            if (single == null && selection != null) details.add(requesterOf(selection));
            String row = rowText(i + 1, link(info), details);
            String artwork = ROW_THUMBNAILS ? MusicEmbeds.thumbnailOf(track) : null;
            if (artwork != null) {
                children.add(Section.of(Thumbnail.fromUrl(artwork), TextDisplay.of(row)));
                continue;
            }
            if (!text.isEmpty()) text.append('\n');
            text.append(row);
        }
        String overflow = overflow(tracks.size(), shown);
        if (!overflow.isEmpty()) {
            if (!text.isEmpty()) text.append('\n');
            text.append(overflow);
        }
        if (!text.isEmpty()) children.add(TextDisplay.of(text.toString()));
    }

    private static Set<String> requesters(List<AudioTrack> tracks) {
        Set<String> names = new HashSet<>();
        for (AudioTrack track : tracks) {
            MusicSelection selection = track.getUserData(MusicSelection.class);
            if (selection != null && !selection.isAiRecommended()) names.add(requesterOf(selection));
            else names.add("");
        }
        return names;
    }

    static String heading(String title, int count, @Nullable String length, @Nullable String requester, @Nullable String note) {
        if (count == 0) return "**" + title + "**\n-# 비어 있음";
        List<String> small = new ArrayList<>();
        if (length != null) small.add(length);
        if (requester != null && !requester.isEmpty()) small.add("요청 " + requester);
        if (note != null && !note.isBlank()) small.add(note);
        String text = "**" + title + "** | " + count + "곡";
        return small.isEmpty() ? text : text + "\n-# " + String.join(" · ", small);
    }

    static String rowText(int number, String link, List<String> details) {
        return number + ". " + link + "\n-# " + String.join(" · ", details);
    }

    static String overflow(int total, int shown) {
        return total > shown ? "-# 외 " + (total - shown) + "곡" : "";
    }

    @Nullable
    static String totalLength(List<AudioTrack> tracks) {
        long total = 0;
        for (AudioTrack track : tracks) {
            if (!track.getInfo().isStream) total += Math.max(0, track.getInfo().length);
        }
        return total <= 0 ? null : totalLength(total);
    }

    static String totalLength(long ms) {
        long minutes = ms / 60_000;
        long hours = minutes / 60;
        if (hours > 0) return hours + "시간" + (minutes % 60 > 0 ? " " + minutes % 60 + "분" : "");
        if (minutes > 0) return minutes + "분";
        return (ms / 1000) + "초";
    }

    private static String progress(MusicPlayerClient client, AudioTrackInfo info) {
        long position = Math.max(0, (long) client.getRealPositionMs());
        if (info.isStream || info.length <= 0) return DurationUtil.formatClock(position / 1000) + " · 라이브";
        return DurationUtil.formatClock(Math.min(position, info.length) / 1000)
                + " / " + DurationUtil.formatClock(info.length / 1000);
    }

    private static String link(AudioTrackInfo info) {
        return MusicEmbeds.titleLink(info);
    }

    private static String requesterOf(MusicSelection selection) {
        String name = DiscordSafe.escaped(MemberUtil.getName(selection.getRequestedMember()), MAX_NAME_LENGTH);
        if (selection.isAutoplay()) return "AI 자동 추천 · " + name;
        return selection.isAiRecommended() ? "AI 추천 · " + name : name;
    }

    private static EmbedBuilder buildEmbed(MusicPlayerClient client, AudioTrack currentPlaying) {
        AudioTrackInfo currentPlayingInfo = currentPlaying.getInfo();
        MusicSelection currentMusicSelection = currentPlaying.getUserData(MusicSelection.class);
        EmbedBuilder builder = new EmbedBuilder()
                .setColor(MusicEmbeds.PRIMARY)
                .setAuthor(currentPlayingInfo.author)
                .setTitle(currentPlayingInfo.title, currentPlayingInfo.uri)
                .setThumbnail(MusicEmbeds.thumbnailOf(currentPlaying))
                .setFooter(currentMusicSelection != null
                        ? MemberUtil.getName(currentMusicSelection.getRequestedMember())
                        : SharedConstant.RELEASE_VERSION)
                .addField(
                        "재생 시간",
                        DurationUtil.formatDurationColon((int) (client.getRealPositionMs() / 1000))
                                + " / "
                                + DurationUtil.formatDurationColon((int) (currentPlayingInfo.length / 1000)),
                        true
                ).addField("출처", MusicEmbeds.sourceLabel(currentPlaying), true)
                .addField("노래 봇", MusicEmbeds.botName(client.getGuild()), true)
                .addField("반복 모드", client.getRepeatModeName(), true)
                .addField("재생 속도", client.getCurrentPlaySpeed() + "배속", true)
                .addField("볼륨", client.getVolume() + "%", true)
                .addField("노래방모드", client.getKaraokeMode().getName(), true)
                .addField("이퀄라이저", client.getCurrentEqualizer().getName(), true)
                .addField("AI 추천", TrackCard.autoplayStatus(client), true);
        List<AudioTrack> queue = client.getTrackQueue();
        if (!queue.isEmpty()) {
            builder.addField("", "아래는 대기열 목록입니다 (" + queue.size() + "곡)", false);
            int rows = Math.min(queue.size(), MAX_QUEUE_ROWS);
            for (int i = 0; i < rows; i++) {
                AudioTrack track = queue.get(i);
                MusicSelection selection = track.getUserData(MusicSelection.class);
                String requester = selection != null ? MemberUtil.getName(selection.getRequestedMember()) : "";
                builder.addField(
                        (i + 1) + ". " + track.getInfo().title,
                        requester + " - " + track.getInfo().uri,
                        false
                );
            }
            if (queue.size() > rows) {
                builder.addField("", "... 외 " + (queue.size() - rows) + "곡", false);
            }
        }
        List<AudioTrack> autoQueue = client.getAutoQueue();
        if (!autoQueue.isEmpty()) {
            builder.addField("다음 추천 (" + autoQueue.size() + "곡)", MusicEmbeds.queuePreview(autoQueue, MAX_QUEUE_ROWS), false);
        }
        return builder;
    }

    @Override
    public String getDescription() {
        return "현재 노래 재생정보와 대기열 정보를 확인합니다";
    }

    @Override
    public String getUsage() {
        return "/" + COMMAND_NAME;
    }
}
