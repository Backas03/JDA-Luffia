package kr.kro.backas.command.music.slash;

import kr.kro.backas.Main;
import kr.kro.backas.SharedConstant;
import kr.kro.backas.command.api.SlashCommandSource;
import kr.kro.backas.music.MusicPlayerClient;
import kr.kro.backas.music.MusicPlayerController;
import kr.kro.backas.util.MemberUtil;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.channel.concrete.VoiceChannel;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.interactions.commands.OptionMapping;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.interactions.commands.build.SlashCommandData;

import java.awt.*;

public class PauseOrResumeSlashCommand implements SlashCommandSource {
    public static final String COMMAND_ARGUMENT = "해제여부";

    @Override
    public SlashCommandData buildCommand() {
        return Commands.slash("일시정지", "노래를 일시정지 하거나 일시정지를 해제합니다")
                .addOption(OptionType.STRING,
                        COMMAND_ARGUMENT,
                        "일시 정지 모드를 해제하려면 해제를 입력하세요",
                        false);
    }

    @Override
    public void onTriggered(SlashCommandInteractionEvent event) {
        Member member = event.getMember();
        OptionMapping option = event.getOption(COMMAND_ARGUMENT);
        VoiceChannel voiceChannel = MemberUtil.getJoinedVoiceChannel(member); // 길드에서만 서비스시 member는 null 이 될수 없음

        // 일시정시 명령어 일때
        if (option == null || option.getAsString().isEmpty()) {
            if (voiceChannel == null) {
                EmbedBuilder builder = new EmbedBuilder()
                        .setColor(Color.decode("#f1554a"))
                        .setAuthor(MemberUtil.getName(member))
                        .setTitle("노래를 일시정지 할 수 없습니다.")
                        .setDescription("노래를 일시정지 하려면 음성채팅방에 먼저 참여해주세요.")
                        .setFooter(SharedConstant.RELEASE_VERSION);
                event.replyEmbeds(builder.build()).queue();
                return;
            }
            MusicPlayerController controller = Main.getLuffia().getMusicPlayerController();
            MusicPlayerClient client = controller.findFromVoiceChannel(voiceChannel);
            if (client == null || !client.hasJoinedToVoiceChannel()) {
                EmbedBuilder builder = new EmbedBuilder()
                        .setColor(Color.decode("#f1554a"))
                        .setAuthor(MemberUtil.getName(member))
                        .setTitle("노래를 일시정지 할 수 없습니다.")
                        .setDescription("현재 재생중인 곡이 없으므로 일시정지 할 수 없습니다.")
                        .setFooter(SharedConstant.RELEASE_VERSION);
                event.replyEmbeds(builder.build()).queue();
                return;
            }
            if (client.pause()) {
                event.reply("현재 재생중인 곡을 일시정지 했습니다.").queue();
                return;
            }
            event.reply("이미 노래가 일시정지 된 상태입니다!").queue();
            return;
        }

        // 일시정지 해제 명령어 일때
        if (voiceChannel == null) {
            EmbedBuilder builder = new EmbedBuilder()
                    .setColor(Color.decode("#f1554a"))
                    .setAuthor(MemberUtil.getName(member))
                    .setTitle("일시정지를 해제 할 수 없습니다.")
                    .setDescription("일시정지를 해제 하려면 음성채팅방에 먼저 참여해주세요.")
                    .setFooter(SharedConstant.RELEASE_VERSION);
            event.replyEmbeds(builder.build()).queue();
            return;
        }
        MusicPlayerController controller = Main.getLuffia().getMusicPlayerController();
        MusicPlayerClient client = controller.findFromVoiceChannel(voiceChannel);
        if (client == null || !client.hasJoinedToVoiceChannel()) {
            EmbedBuilder builder = new EmbedBuilder()
                    .setColor(Color.decode("#f1554a"))
                    .setAuthor(MemberUtil.getName(member))
                    .setTitle("일시정지를 해제 할 수 없습니다.")
                    .setDescription("현재 재생중인 곡이 없으므로 일시정지를 해제 할 수 없습니다.")
                    .setFooter(SharedConstant.RELEASE_VERSION);
            event.replyEmbeds(builder.build()).queue();
            return;
        }
        if (client.resume()) {
            event.reply("현재 재생중인 곡의 일시정지 상태를 해제 했습니다.").queue();
            return;
        }
        event.reply("일시정지 상태가 아닙니다!").queue();
    }
}
