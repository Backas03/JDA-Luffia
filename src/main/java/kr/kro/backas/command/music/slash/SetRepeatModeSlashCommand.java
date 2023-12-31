package kr.kro.backas.command.music.slash;

import kr.kro.backas.Main;
import kr.kro.backas.SharedConstant;
import kr.kro.backas.command.api.SlashCommandSource;
import kr.kro.backas.music.MusicPlayerClient;
import kr.kro.backas.music.MusicPlayerController;
import kr.kro.backas.music.RepeatMode;
import kr.kro.backas.util.MemberUtil;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.channel.concrete.VoiceChannel;
import net.dv8tion.jda.api.events.interaction.command.CommandAutoCompleteInteractionEvent;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.interactions.commands.Command;
import net.dv8tion.jda.api.interactions.commands.OptionMapping;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.interactions.commands.build.SlashCommandData;

import java.awt.*;
import java.util.Arrays;
import java.util.Collection;
import java.util.stream.Stream;

public class SetRepeatModeSlashCommand implements SlashCommandSource {

    public static final String COMMAND_NAME = "반복모드";
    public static final String COMMAND_ARGUMENT_NAME = "모드";

    @Override
    public SlashCommandData buildCommand() {
        return Commands.slash(COMMAND_NAME, "반복 모드를 설정합니다")
                .addOption(OptionType.STRING,
                        COMMAND_ARGUMENT_NAME,
                        "반복 모드를 설정합니다",
                        true,
                        true);
    }

    @Override
    public void onTriggered(SlashCommandInteractionEvent event) {
        RepeatMode mode = null;
        OptionMapping arg = event.getOption(COMMAND_ARGUMENT_NAME);
        if (arg == null) {
            event.reply("반복 모드를 입력해주세요").queue();
            return;
        }
        for (RepeatMode repeatMode : RepeatMode.values()) {
            String argString = arg.getAsString();
            if (argString.equals(repeatMode.getName())) {
                mode = repeatMode;
                break;
            }
        }
        if (mode == null) {
            event.reply("올바른 반복 모드를 입력해주세요").queue();
            return;
        }
        Member member = event.getMember();
        VoiceChannel voiceChannel = MemberUtil.getJoinedVoiceChannel(member); // 길드에서만 서비스시 member는 null 이 될수 없음
        if (voiceChannel == null) {
            EmbedBuilder builder = new EmbedBuilder()
                    .setColor(Color.decode("#f1554a"))
                    .setAuthor(MemberUtil.getName(member))
                    .setTitle("반복재생 모드를 설정할 수 없습니다.")
                    .setDescription("반복재생 모드를 설정하려면 음성채팅방에 먼저 참여해주세요.")
                    .setFooter(SharedConstant.RELEASE_VERSION);
            event.replyEmbeds(builder.build()).queue();
            return;
        }
        MusicPlayerController controller = Main.getLuffia().getMusicPlayerController();
        MusicPlayerClient client = controller.findFromVoiceChannel(voiceChannel);
        if (client == null || !client.hasJoinedToVoiceChannel() || !client.isNowPlaying()) {
            EmbedBuilder builder = new EmbedBuilder()
                    .setColor(Color.decode("#f1554a"))
                    .setAuthor(MemberUtil.getName(member))
                    .setTitle("반복재생 모드를 설정할 수 없습니다.")
                    .setDescription("현재 재생중인 곡이 없으므로 반복재생 모드를 설정할 수 없습니다")
                    .setFooter(SharedConstant.RELEASE_VERSION);
            event.replyEmbeds(builder.build()).queue();
            return;
        }
        client.setRepeatMode(mode);
        event.reply("반복 모드를 \"" + mode.getName() + "\" (으)로 설정했습니다.").queue();
    }

    @Override
    public void onCommandAutoCompleteInteraction(CommandAutoCompleteInteractionEvent event) {
        if (event.getName().equals(COMMAND_NAME) && event.getFocusedOption().getName().equals(COMMAND_ARGUMENT_NAME)) {
            String[] options = Arrays.stream(RepeatMode.values())
                    .map(RepeatMode::getName)
                    .toArray(String[]::new);
            Collection<Command.Choice> optionsList = Stream.of(options)
                            .filter(word -> word.isEmpty() || word.startsWith(event.getFocusedOption().getValue()))
                            .map(word -> new Command.Choice(word, word))
                            .toList();
            System.out.println(optionsList);
            event.replyChoices(optionsList).queue();
        }
    }
}
