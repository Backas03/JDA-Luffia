package kr.kro.backas.command.music.slash;

import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import kr.kro.backas.Main;
import kr.kro.backas.SharedConstant;
import kr.kro.backas.command.api.SlashCommandSource;
import kr.kro.backas.music.MusicEmbeds;
import kr.kro.backas.music.MusicPlayerClient;
import kr.kro.backas.music.MusicPlayerController;
import kr.kro.backas.music.lyrics.Lyrics;
import kr.kro.backas.music.lyrics.LyricsSession;
import kr.kro.backas.util.MemberUtil;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.entities.channel.concrete.VoiceChannel;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.interactions.InteractionHook;
import net.dv8tion.jda.api.interactions.commands.OptionMapping;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.interactions.commands.build.OptionData;
import net.dv8tion.jda.api.interactions.commands.build.SlashCommandData;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

public class LyricsSlashCommand implements SlashCommandSource {
    public static final String COMMAND_NAME = "가사";
    public static final String MODE_ARGUMENT = "모드";
    public static final String OFFSET_ARGUMENT = "오프셋";
    private static final String MODE_LIVE = "LIVE";
    private static final String MODE_FULL = "FULL";
    private static final String MODE_OFF = "OFF";
    private static final int EMBED_TEXT_LIMIT = 4000;
    private static final int MAX_FULL_PAGES = 3;

    @Override
    public SlashCommandData buildCommand() {
        return Commands.slash(COMMAND_NAME, getDescription())
                .addOptions(
                        new OptionData(OptionType.STRING, MODE_ARGUMENT, "실시간(기본), 전체, 끄기", false)
                                .addChoice("실시간", MODE_LIVE)
                                .addChoice("전체", MODE_FULL)
                                .addChoice("끄기", MODE_OFF),
                        new OptionData(OptionType.INTEGER, OFFSET_ARGUMENT,
                                "가사가 빠르면 올리고 느리면 내리세요 (밀리초, 기본 " + LyricsSession.DEFAULT_OFFSET_MS + ")", false)
                                .setRequiredRange(-10000, 10000)
                );
    }

    @Override
    public void onTriggered(SlashCommandInteractionEvent event) {
        Member member = event.getMember();
        VoiceChannel voiceChannel = MemberUtil.getJoinedVoiceChannel(member);
        if (voiceChannel == null) {
            event.replyEmbeds(MusicEmbeds.error(member, "가사를 표시할 수 없습니다",
                    "가사를 보려면 음성채팅방에 먼저 참여해주세요.").build()).queue();
            return;
        }
        MusicPlayerController controller = Main.getLuffia().getMusicPlayerController();
        MusicPlayerClient client = controller.findFromVoiceChannel(voiceChannel);
        AudioTrack track = client == null ? null : client.getCurrentPlaying();
        if (track == null) {
            event.replyEmbeds(MusicEmbeds.error(member, "가사를 표시할 수 없습니다",
                    "현재 재생중인 곡이 없습니다.").build()).queue();
            return;
        }
        OptionMapping modeOption = event.getOption(MODE_ARGUMENT);
        String mode = modeOption == null ? MODE_LIVE : modeOption.getAsString();
        OptionMapping offsetOption = event.getOption(OFFSET_ARGUMENT);
        Long offset = offsetOption == null ? null : offsetOption.getAsLong();

        if (MODE_OFF.equals(mode)) {
            boolean stopped = client.stopLyrics("가사 표시를 껐습니다");
            event.reply(stopped ? "가사 표시를 껐습니다." : "표시 중인 가사가 없습니다.").queue();
            return;
        }
        LyricsSession existing = client.getLyricsSession();
        if (MODE_LIVE.equals(mode) && existing != null && existing.isForTrack(track) && offset != null) {
            existing.setOffsetMs(offset);
            event.reply("가사 오프셋을 " + offset + "ms 로 변경했습니다.").queue();
            return;
        }

        InteractionHook hook = event.deferReply().complete();
        long offsetMs = offset == null ? LyricsSession.DEFAULT_OFFSET_MS : offset;
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
                        hook.editOriginalEmbeds(MusicEmbeds.error(member, "가사를 불러오지 못했습니다",
                                error.getCause() == null ? error.getMessage() : error.getCause().getMessage()).build()).queue();
                        return;
                    }
                    if (lyrics == null || lyrics.instrumental() || (!lyrics.hasPlain() && !lyrics.hasSynced())) {
                        hook.editOriginalEmbeds(MusicEmbeds.error(member, "가사를 찾지 못했습니다",
                                track.getInfo().author + " - " + track.getInfo().title).build()).queue();
                        return;
                    }
                    if (MODE_LIVE.equals(mode) && lyrics.hasSynced()) {
                        startLive(hook, client, track, lyrics, offsetMs, member);
                        return;
                    }
                    showFull(hook, track, lyrics, member, MODE_LIVE.equals(mode));
                });
    }

    private void startLive(InteractionHook hook, MusicPlayerClient client, AudioTrack track,
                           Lyrics lyrics, long offsetMs, Member member) {
        client.stopLyrics("새 가사 표시로 대체되었습니다");
        MessageEmbed initial = new EmbedBuilder()
                .setColor(MusicEmbeds.PRIMARY)
                .setTitle(track.getInfo().title, track.getInfo().uri)
                .setThumbnail(MusicEmbeds.thumbnailOf(track))
                .setDescription("**♪**")
                .setFooter("가사 동기화 준비 중 (" + lyrics.artistName() + " - " + lyrics.trackName() + ")")
                .build();
        hook.editOriginalEmbeds(initial).queue(message -> {
            LyricsSession session = new LyricsSession(client, track, lyrics, message,
                    Main.getLuffia().getMusicPlayerController().getScheduler(), offsetMs);
            client.setLyricsSession(session);
            session.start();
        }, e -> hook.editOriginalEmbeds(MusicEmbeds.error(member, "가사 메시지를 만들지 못했습니다", e.getMessage()).build()).queue());
    }

    private void showFull(InteractionHook hook, AudioTrack track, Lyrics lyrics, Member member, boolean wantedLive) {
        String text = lyrics.hasPlain() ? lyrics.plain() : joinSynced(lyrics);
        List<String> pages = paginate(text);
        List<MessageEmbed> embeds = new ArrayList<>();
        for (int i = 0; i < pages.size() && i < MAX_FULL_PAGES; i++) {
            EmbedBuilder builder = new EmbedBuilder()
                    .setColor(MusicEmbeds.PRIMARY)
                    .setDescription(pages.get(i));
            if (i == 0) {
                builder.setTitle(track.getInfo().title, track.getInfo().uri)
                        .setThumbnail(MusicEmbeds.thumbnailOf(track))
                        .setAuthor(lyrics.artistName() + " - " + lyrics.trackName());
            }
            if (i == Math.min(pages.size(), MAX_FULL_PAGES) - 1) {
                String footer = wantedLive ? "이 곡은 타임스탬프 가사가 없어 전체 가사로 표시합니다" : SharedConstant.RELEASE_VERSION;
                builder.setFooter(footer + " · 요청: " + MemberUtil.getName(member));
            }
            embeds.add(builder.build());
        }
        hook.editOriginalEmbeds(embeds).queue();
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

    @Override
    public String getDescription() {
        return "현재 재생중인 곡의 가사를 표시합니다";
    }

    @Override
    public String getUsage() {
        return "/" + COMMAND_NAME + " (모드) (오프셋)";
    }
}
