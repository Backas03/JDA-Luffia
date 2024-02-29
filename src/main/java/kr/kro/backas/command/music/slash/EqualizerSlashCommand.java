package kr.kro.backas.command.music.slash;

import kr.kro.backas.Main;
import kr.kro.backas.SharedConstant;
import kr.kro.backas.command.api.SlashCommandSource;
import kr.kro.backas.music.MusicPlayerClient;
import kr.kro.backas.music.filter.ConfiguredEqualizer;
import kr.kro.backas.util.MemberUtil;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.events.interaction.command.CommandAutoCompleteInteractionEvent;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.interactions.commands.Command;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.interactions.commands.build.SlashCommandData;

import java.awt.*;
import java.util.Arrays;
import java.util.Collection;
import java.util.stream.Stream;

public class EqualizerSlashCommand implements SlashCommandSource {
    public static final String COMMAND_NAME = "이퀄라이저";
    public static final String COMMAND_ARGUMENT = "설정";

    @Override
    public String getDescription() {
        return "이퀄라이저를 설정합니다.";
    }

    @Override
    public String getUsage() {
        return "/" + COMMAND_NAME + "[" + COMMAND_ARGUMENT + "]";
    }

    @Override
    public SlashCommandData buildCommand() {
        return Commands.slash(COMMAND_NAME, getUsage())
                .addOption(OptionType.STRING, COMMAND_ARGUMENT, getDescription(), true, true);
    }

    @Override
    public void onTriggered(SlashCommandInteractionEvent event) {
        EmbedBuilder builder = new EmbedBuilder()
                .setColor(Color.decode("#f1554a"))
                .setAuthor(MemberUtil.getName(event.getMember()))
                .setTitle("이퀄라이저를 설정할 수 없습니다");
        if (!event.getMember().getVoiceState().inAudioChannel()) {
            event.replyEmbeds(builder.setDescription("이퀄라이저를 변경하려면 음성채널에 먼저 참여해주세요").build()).queue();
            return;
        }
        MusicPlayerClient client = Main.getLuffia()
                .getMusicPlayerController()
                .findFromVoiceChannel(event.getMember().getVoiceState().getChannel().asVoiceChannel());
        if (client == null) {
            event.replyEmbeds(builder.setDescription("재생중인 음악이 없어 노래방 모드를 설정할 수 없습니다").build()).queue();
            return;
        }
        String option = event.getOption(COMMAND_ARGUMENT).getAsString();
        ConfiguredEqualizer eq = ConfiguredEqualizer.fromName(option);
        if (eq == null) {
            event.replyEmbeds(builder.setDescription("해당 이퀄라이저를 찾을 수 없습니다").build()).queue();
            return;
        }
        ConfiguredEqualizer before = client.getCurrentEqualizer();
        client.setEqualizer(eq); // 모드 변경
        event.replyEmbeds(new EmbedBuilder()
                .setColor(Color.GREEN)
                .setAuthor(MemberUtil.getName(event.getMember()))
                .setTitle("이퀄라이저 설정이 변경됩니다")
                .setDescription("이퀄라이저가 즉시 적용되지 않을 경우 다음 곡부터 적용됩니다")
                .addField("이퀄라이저 설정",
                        before.getName() + " -> " + eq.getName()
                        , false)
                .setFooter(SharedConstant.RELEASE_VERSION)
                .build()
        ).queue();
    }

    @Override
    public void onCommandAutoCompleteInteraction(CommandAutoCompleteInteractionEvent event) {
        if (event.getName().equals(COMMAND_NAME) && event.getFocusedOption().getName().equals(COMMAND_ARGUMENT)) {
            String[] options = Arrays.stream(ConfiguredEqualizer.values())
                    .map(ConfiguredEqualizer::getName)
                    .toArray(String[]::new);

            Collection<Command.Choice> optionsList = Stream.of(options)
                    .filter(word -> word.isEmpty() || word.startsWith(event.getFocusedOption().getValue()))
                    .map(word -> new Command.Choice(word, word))
                    .toList();
            event.replyChoices(optionsList).queue();
        }
    }
}
