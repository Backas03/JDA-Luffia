package kr.kro.backas.command.music.slash;

import kr.kro.backas.Main;
import kr.kro.backas.command.api.SlashCommandSource;
import kr.kro.backas.music.Identifier;
import kr.kro.backas.music.MusicEmbeds;
import kr.kro.backas.music.MusicPlayerController;
import kr.kro.backas.music.MusicQueryParser;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.interactions.commands.OptionMapping;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.interactions.commands.build.OptionData;
import net.dv8tion.jda.api.interactions.commands.build.SlashCommandData;

public class PlaySlashCommand implements SlashCommandSource {
    public static final String COMMAND_NAME = "재생";
    public static final String COMMAND_ARGUMENT = "검색어";
    public static final String SOURCE_ARGUMENT = "출처";

    @Override
    public SlashCommandData buildCommand() {
        OptionData source = new OptionData(OptionType.STRING, SOURCE_ARGUMENT,
                "검색어로 검색할 서비스 (기본: 유튜브). 링크를 입력한 경우 무시됩니다", false);
        for (Identifier identifier : Identifier.values()) {
            if (identifier.isSearch()) {
                source.addChoice(identifier.getDisplayName(), identifier.name());
            }
        }
        return Commands.slash(COMMAND_NAME, getDescription())
                .addOption(OptionType.STRING,
                        COMMAND_ARGUMENT,
                        "검색어 또는 유튜브/스포티파이 링크 (곡, 앨범, 플레이리스트)",
                        true)
                .addOptions(source);
    }

    @Override
    public void onTriggered(SlashCommandInteractionEvent event) {
        OptionMapping option = event.getOption(COMMAND_ARGUMENT);
        if (option == null) {
            event.reply("검색어를 입력해주세요").queue();
            return;
        }
        Identifier preferred = null;
        OptionMapping sourceOption = event.getOption(SOURCE_ARGUMENT);
        if (sourceOption != null) {
            try {
                preferred = Identifier.valueOf(sourceOption.getAsString());
            } catch (IllegalArgumentException ignore) {
            }
        }
        MusicQueryParser.ParsedQuery parsed = MusicQueryParser.parse(option.getAsString(), preferred);

        MusicPlayerController controller = Main.getLuffia().getMusicPlayerController();
        if (parsed.requiresSpotify() && !controller.getSourceRegistry().isSpotifyEnabled()) {
            event.replyEmbeds(MusicEmbeds.error(event.getMember(),
                    "스포티파이를 사용할 수 없습니다",
                    "봇에 스포티파이 API 키가 설정되어 있지 않습니다. 유튜브 링크 또는 검색어를 이용해주세요.").build()).queue();
            return;
        }
        controller.search(parsed.identifier(), parsed.query(), event.getMember(), event);
    }

    @Override
    public String getDescription() {
        return "검색어 또는 유튜브/스포티파이 링크로 노래를 재생합니다";
    }

    @Override
    public String getUsage() {
        return "/" + COMMAND_NAME + " [검색어 또는 링크] (출처)";
    }
}
