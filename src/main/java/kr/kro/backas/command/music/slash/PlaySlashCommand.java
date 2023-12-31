package kr.kro.backas.command.music.slash;

import kr.kro.backas.Main;
import kr.kro.backas.command.api.SlashCommandSource;
import kr.kro.backas.music.Identifier;
import kr.kro.backas.music.service.youtube.YoutubeService;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.interactions.commands.OptionMapping;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.interactions.commands.build.SlashCommandData;

public class PlaySlashCommand implements SlashCommandSource {
    public static final String COMMAND_ARGUMENT = "검색어or유튜브url";

    @Override
    public SlashCommandData buildCommand() {
        return Commands.slash("재생", "유튜브에서 노래를 검색 후 재생합니다")
                .addOption(OptionType.STRING,
                        COMMAND_ARGUMENT,
                        "검색어로 검색 또는 유튜브 URL 을 통한 검색으로 노래를 검색합니다",
                        true);
    }

    @Override
    public void onTriggered(SlashCommandInteractionEvent event) {
        OptionMapping option = event.getOption(COMMAND_ARGUMENT);
        if (option == null) {
            event.reply("검색어를 입력해주세요").queue();
            return;
        }
        String arg = option.getAsString();
        String youtubeURL = YoutubeService.extractVideoId(arg);
        if (youtubeURL != null) {
            arg = youtubeURL;
        }
        Main.getLuffia()
                .getMusicPlayerController()
                .search(Identifier.YOUTUBE, arg, event.getMember(), event);
    }
}
