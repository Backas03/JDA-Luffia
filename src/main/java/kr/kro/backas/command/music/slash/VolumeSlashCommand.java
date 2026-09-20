package kr.kro.backas.command.music.slash;

import kr.kro.backas.Main;
import kr.kro.backas.SharedConstant;
import kr.kro.backas.command.api.SlashCommandSource;
import kr.kro.backas.music.MusicEmbeds;
import kr.kro.backas.music.MusicPlayerClient;
import kr.kro.backas.util.MemberUtil;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.channel.concrete.VoiceChannel;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.interactions.commands.OptionMapping;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.interactions.commands.build.OptionData;
import net.dv8tion.jda.api.interactions.commands.build.SlashCommandData;

public class VolumeSlashCommand implements SlashCommandSource {
    public static final String COMMAND_NAME = "볼륨";
    public static final String COMMAND_ARGUMENT_NAME = "퍼센트";
    public static final int MIN_VOLUME = 0;
    public static final int MAX_VOLUME = 200;

    @Override
    public SlashCommandData buildCommand() {
        return Commands.slash(COMMAND_NAME, getDescription())
                .addOptions(new OptionData(OptionType.INTEGER, COMMAND_ARGUMENT_NAME,
                        "볼륨 퍼센트 (" + MIN_VOLUME + " ~ " + MAX_VOLUME + ", 기본 " + MusicPlayerClient.DEFAULT_VOLUME + "). 비우면 현재 볼륨을 보여줍니다", false)
                        .setRequiredRange(MIN_VOLUME, MAX_VOLUME));
    }

    @Override
    public void onTriggered(SlashCommandInteractionEvent event) {
        Member member = event.getMember();
        VoiceChannel voiceChannel = MemberUtil.getJoinedVoiceChannel(member);
        if (voiceChannel == null) {
            event.replyEmbeds(MusicEmbeds.error(member,
                    "볼륨을 변경할 수 없습니다.",
                    "볼륨을 변경하려면 음성채팅방에 먼저 참여해주세요.").build()).queue();
            return;
        }
        MusicPlayerClient client = Main.getLuffia().getMusicPlayerController().findFromVoiceChannel(voiceChannel);
        if (client == null) {
            event.replyEmbeds(MusicEmbeds.error(member,
                    "볼륨을 변경할 수 없습니다.",
                    "해당 채널에서 음악을 재생중인 노래 봇이 없습니다.").build()).queue();
            return;
        }
        OptionMapping option = event.getOption(COMMAND_ARGUMENT_NAME);
        if (option == null) {
            event.replyEmbeds(new EmbedBuilder()
                    .setColor(MusicEmbeds.PRIMARY)
                    .setAuthor(MemberUtil.getName(member))
                    .setTitle("현재 볼륨")
                    .setDescription(client.getVolume() + "%")
                    .addField("노래 봇", MusicEmbeds.botName(client.getMusicBot()), false)
                    .setFooter(SharedConstant.RELEASE_VERSION)
                    .build()).queue();
            return;
        }
        int volume = Math.max(MIN_VOLUME, Math.min(MAX_VOLUME, option.getAsInt()));
        int before = client.getVolume();
        client.setVolume(volume);
        event.replyEmbeds(new EmbedBuilder()
                .setColor(MusicEmbeds.SUCCESS)
                .setAuthor(MemberUtil.getName(member))
                .setTitle("볼륨을 변경했습니다")
                .addField("볼륨", before + "% -> " + volume + "%", false)
                .addField("노래 봇", MusicEmbeds.botName(client.getMusicBot()), false)
                .setFooter(SharedConstant.RELEASE_VERSION)
                .build()).queue();
    }

    @Override
    public String getDescription() {
        return "노래 봇의 볼륨을 변경하거나 확인합니다";
    }

    @Override
    public String getUsage() {
        return "/" + COMMAND_NAME + " (" + COMMAND_ARGUMENT_NAME + ")";
    }
}
