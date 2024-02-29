package kr.kro.backas.command.music.slash;

import kr.kro.backas.Main;
import kr.kro.backas.SharedConstant;
import kr.kro.backas.command.api.SlashCommandSource;
import kr.kro.backas.music.MusicPlayerClient;
import kr.kro.backas.util.MemberUtil;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.interactions.commands.build.SlashCommandData;

import java.awt.*;

public class PlaySpeedSlashCommand implements SlashCommandSource {
    public static final String COMMAND_NAME = "속도";
    public static final String COMMAND_ARGUMENT_NAME = "배수";

    @Override
    public String getDescription() {
        return "재생 속도를 변경합니다";
    }

    @Override
    public String getUsage() {
        return "/" + COMMAND_NAME + " [" + COMMAND_ARGUMENT_NAME + "]";
    }

    @Override
    public SlashCommandData buildCommand() {
        return Commands.slash(COMMAND_NAME, getDescription())
                .addOption(OptionType.STRING, COMMAND_ARGUMENT_NAME, "재생 속도 배수를 입력하세요", true);
    }

    @Override
    public void onTriggered(SlashCommandInteractionEvent event) {
        EmbedBuilder builder = new EmbedBuilder()
                .setColor(Color.decode("#f1554a"))
                .setAuthor(MemberUtil.getName(event.getMember()))
                .setTitle("노래 재생속도를 변경할 수 없습니다");
        if (!event.getMember().getVoiceState().inAudioChannel()) {
            event.replyEmbeds(builder.setDescription("재생속도를 변경하려면 음성채널에 먼저 참여해주세요").build()).queue();
            return;
        }
        double speed;
        try {
            speed = event.getOption(COMMAND_ARGUMENT_NAME).getAsDouble();
        } catch (IllegalArgumentException ignore) {
            event.replyEmbeds(builder.setDescription("재생속도가 올바르지 않습니다").build()).queue();
            return;
        }
        if (speed < 0.1) {
            event.replyEmbeds(builder.setDescription("재생속도는 0.1 미만이 될 수 없습니다").build()).queue();
            return;
        }
        if (speed > 3.0) {
            event.replyEmbeds(builder.setDescription("재생속도는 3.0 초과가 될 수 없습니다").build()).queue();
            return;
        }
        MusicPlayerClient client = Main.getLuffia()
                .getMusicPlayerController()
                .findFromVoiceChannel(event.getMember().getVoiceState().getChannel().asVoiceChannel());
        if (client == null) {
            event.replyEmbeds(builder.setDescription("재생중인 음악이 없어 재생속도를 변경할 수 없습니다.").build()).queue();
            return;
        }
        double originSpeed = client.getCurrentPlaySpeed();
        client.setPlaySpeed(speed);
        event.replyEmbeds(new EmbedBuilder()
                .setColor(Color.GREEN)
                .setAuthor(MemberUtil.getName(event.getMember()))
                .setTitle("노래 재생속도가 변경됩니다")
                .setDescription("재생속도가 변경되지 않을 경우 다음 곡부터 적용됩니다")
                .addField("재생속도",
                         originSpeed + "배속 -> " + speed + "배속"
                        , false)
                .setFooter(SharedConstant.RELEASE_VERSION)
                .build()
        ).queue();
    }
}
