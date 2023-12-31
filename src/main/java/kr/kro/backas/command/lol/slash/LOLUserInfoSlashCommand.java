package kr.kro.backas.command.lol.slash;

import kr.kro.backas.SharedConstant;
import kr.kro.backas.command.api.SlashCommandSource;
import kr.kro.backas.game.lol.LOLUserInfo;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.interactions.commands.OptionMapping;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.interactions.commands.build.SlashCommandData;

import java.awt.*;
import java.util.concurrent.CompletableFuture;

public class LOLUserInfoSlashCommand implements SlashCommandSource {
    private static final String COMMAND_NAME = "롤정보";
    private static final String COMMAND_ARGUMENT_NAME = "닉네임";
    @Override
    public SlashCommandData buildCommand() {
        return Commands.slash(COMMAND_NAME, getDescription())
                .addOption(OptionType.STRING,
                        COMMAND_ARGUMENT_NAME,
                        "닉네임으로 롤 정보를 확인합니다",
                        true);
    }

    @Override
    public void onTriggered(SlashCommandInteractionEvent event) {
        OptionMapping arg = event.getOption(COMMAND_ARGUMENT_NAME);
        if (arg == null || arg.getAsString().isEmpty()) {
            event.reply("닉네임을 입력해주세요").queue();
            return;
        }
        String argString = arg.getAsString();
        String[] splitByTag = argString.split("#");
        String nickname = splitByTag[0];
        String tag = splitByTag.length >= 2 ? splitByTag[1] : "KR1";
        if (nickname == null) {
            event.reply("닉네임을 입력해주세요.").queue();
            return;
        }
        LOLUserInfo info = new LOLUserInfo(nickname, tag);
        if (!info.exists()) {
            event.replyEmbeds(
                    new EmbedBuilder()
                        .setColor(Color.decode("#f1554a"))
                        .setAuthor(event.getMember().getNickname())
                        .setTitle("\"" + nickname + "#" + tag + "\" 소환사를 찾을 수 없습니다.")
                        .setDescription("닉네임 또는 태그를 다시한번 확인해주세요.")
                        .setFooter(SharedConstant.RELEASE_VERSION).build()
            ).queue();
            return;
        }
        CompletableFuture.supplyAsync(() -> {
            event.replyEmbeds(info.getInfoMessage().build()).queue();
            return false;
        });
    }

    @Override
    public String getDescription() {
        return "플레이어의 롤 정보를 확인합니다";
    }

    @Override
    public String getUsage() {
        return "/" + COMMAND_NAME + " [닉네임]";
    }
}
