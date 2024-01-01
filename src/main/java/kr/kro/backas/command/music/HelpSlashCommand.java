package kr.kro.backas.command.music;

import kr.kro.backas.Main;
import kr.kro.backas.SharedConstant;
import kr.kro.backas.command.api.CommandManager;
import kr.kro.backas.command.api.SlashCommandSource;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.interactions.commands.build.SlashCommandData;

import java.awt.*;
import java.util.Map;

public class HelpSlashCommand implements SlashCommandSource {

    @Override
    public String getDescription() {
        return "Luffia 의 도움말을 확인합니다";
    }

    @Override
    public String getUsage() {
        return "/도움말";
    }

    @Override
    public SlashCommandData buildCommand() {
        return Commands.slash("도움말", getDescription());
    }

    @Override
    public void onTriggered(SlashCommandInteractionEvent event) {
        CommandManager commandManager = Main.getLuffia()
                .getCommandManager();
        Map<String, SlashCommandSource> slashCommandSources = commandManager.getSlashCommandSources();

        // * 현재 Luffia는 오전 0시 ~ 10시 사이동안 서비스를 하지 않습니다.
        // * 24시간 서비스시 해당 문구가 사라질 예정입니다.
        EmbedBuilder builder = new EmbedBuilder()
                .setColor(Color.decode("#75f971"))
                .setTitle("Luffia 도움말")
                .setDescription("""
                            Java Discord API(JDA) 로 여러 기능을 서비스하는 디스코드 봇 Luffia 입니다.
                            이 봇은 <@397589473531002882> bagkaseu(박카스#9970) 에 의해 제작되었습니다.
                            해당 봇의 오류나 건의, 문의사항이 있을시 제작자에게 문의 또는 Luffia Github에 Issue 바랍니다.
                            

                            아래는 해당 봇의 기능입니다.""");
        for (Map.Entry<String, SlashCommandSource> entry : slashCommandSources.entrySet()) {
            SlashCommandSource slashCommandSource = entry.getValue();
            builder.addField(slashCommandSource.getUsage(), slashCommandSource.getDescription(), false);
        }
        builder.setAuthor(
                "Luffia Github (click)" +
                        "\n" + SharedConstant.LICENSE,
                SharedConstant.GITHUB
        );

        builder.setFooter(SharedConstant.RELEASE_VERSION);
        event.replyEmbeds(builder.build()).queue();
    }
}
