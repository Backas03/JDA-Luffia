package kr.kro.backas.command.music.slash;

import kr.kro.backas.Main;
import kr.kro.backas.SharedConstant;
import kr.kro.backas.command.api.SlashCommandSource;
import kr.kro.backas.music.MusicPlayerClient;
import kr.kro.backas.util.MemberUtil;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.interactions.commands.build.SlashCommandData;

import java.awt.*;

public class KaraokeModeSlashCommand implements SlashCommandSource {
    public static final String COMMAND_NAME = "노래방모드";

    @Override
    public String getDescription() {
        return "노래방 모드를 설정합니다";
    }

    @Override
    public String getUsage() {
        return "/" + COMMAND_NAME;
    }

    @Override
    public SlashCommandData buildCommand() {
        return Commands.slash(COMMAND_NAME, getDescription());
    }

    @Override
    public void onTriggered(SlashCommandInteractionEvent event) {
        EmbedBuilder builder = new EmbedBuilder()
                .setColor(Color.decode("#f1554a"))
                .setAuthor(MemberUtil.getName(event.getMember()))
                .setTitle("노래방 모드를 설정할 수 없습니다");
        if (!event.getMember().getVoiceState().inAudioChannel()) {
            event.replyEmbeds(builder.setDescription("노래방 모드로 변경하려면 음성채널에 먼저 참여해주세요").build()).queue();
            return;
        }

        MusicPlayerClient client = Main.getLuffia()
                .getMusicPlayerController()
                .findFromVoiceChannel(event.getMember().getVoiceState().getChannel().asVoiceChannel());
        if (client == null) {
            event.replyEmbeds(builder.setDescription("재생중인 음악이 없어 노래방 모드를 설정할 수 없습니다.").build()).queue();
            return;
        }
        client.setKaraokeMode(!client.isKaraokeMode()); // 모드 변경
        event.replyEmbeds(new EmbedBuilder()
                .setColor(Color.GREEN)
                .setAuthor(MemberUtil.getName(event.getMember()))
                .setTitle("노래방 모드가 변경됩니다")
                .setDescription("노래방 모드로 변경되지 않을 경우 다음 곡부터 적용됩니다")
                .addField("노래방 모드",
                        client.isKaraokeMode() ? "비활성화 -> 활성화" : "활성화 -> 비활성화"
                        , false)
                .setFooter(SharedConstant.RELEASE_VERSION)
                .build()
        ).queue();
    }
}
