package kr.kro.backas.command.music.slash;

import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import kr.kro.backas.Main;
import kr.kro.backas.command.api.SlashCommandSource;
import kr.kro.backas.music.MusicEmbeds;
import kr.kro.backas.music.MusicPlayerClient;
import kr.kro.backas.music.lyrics.LyricsPresenter;
import kr.kro.backas.music.lyrics.LyricsSession;
import kr.kro.backas.util.MemberUtil;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.channel.concrete.VoiceChannel;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.interactions.InteractionHook;
import net.dv8tion.jda.api.interactions.commands.OptionMapping;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.interactions.commands.build.OptionData;
import net.dv8tion.jda.api.interactions.commands.build.SlashCommandData;

public class LyricsSlashCommand implements SlashCommandSource {
    public static final String COMMAND_NAME = "가사";
    public static final String MODE_ARGUMENT = "모드";
    public static final String OFFSET_ARGUMENT = "오프셋";
    private static final String MODE_LIVE = "LIVE";
    private static final String MODE_FULL = "FULL";
    private static final String MODE_OFF = "OFF";

    @Override
    public SlashCommandData buildCommand() {
        return Commands.slash(COMMAND_NAME, getDescription())
                .addOptions(
                        new OptionData(OptionType.STRING, MODE_ARGUMENT, "실시간(기본, 항상 켜져 있음), 전체, 끄기(다시 켜려면 실시간)", false)
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
        MusicPlayerClient client = Main.getLuffia().getMusicPlayerController().findFromVoiceChannel(voiceChannel);
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
            boolean stopped = client.disableAutoLyrics("가사 표시를 껐습니다");
            event.reply(stopped ? "가사 표시를 껐습니다. 다시 켜려면 `/가사`를 실행하세요." : "가사 표시가 이미 꺼져 있습니다.").queue();
            return;
        }
        LyricsSession existing = client.getLyricsSession();
        if (MODE_LIVE.equals(mode) && existing != null && existing.isForTrack(track) && offset != null) {
            existing.setOffsetMs(offset);
            client.setLyricsOffsetMs(offset);
            event.reply("가사 오프셋을 " + offset + "ms 로 변경했습니다.").queue();
            return;
        }

        long offsetMs = offset == null ? client.getLyricsOffsetMs() : offset;
        InteractionHook hook = event.deferReply().complete();
        if (MODE_LIVE.equals(mode)) {
            client.enableAutoLyrics(event.getMessageChannel(), offsetMs);
        }
        LyricsPresenter.presentViaHook(client, track, hook, offsetMs, MODE_LIVE.equals(mode), member);
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
