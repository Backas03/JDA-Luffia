package kr.kro.backas.command.music.slash;

import kr.kro.backas.Main;
import kr.kro.backas.SharedConstant;
import kr.kro.backas.command.api.SlashCommandSource;
import kr.kro.backas.music.MusicEmbeds;
import kr.kro.backas.music.MusicPlayerClient;
import kr.kro.backas.music.filter.KaraokeMode;
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

public class KaraokeModeSlashCommand implements SlashCommandSource {
    public static final String COMMAND_NAME = "노래방모드";
    public static final String COMMAND_ARGUMENT_NAME = "모드";

    @Override
    public String getDescription() {
        return "노래방 모드를 설정합니다 (에코, 보컬 제거, 끄기)";
    }

    @Override
    public String getUsage() {
        return "/" + COMMAND_NAME + " (" + COMMAND_ARGUMENT_NAME + ")";
    }

    @Override
    public SlashCommandData buildCommand() {
        OptionData mode = new OptionData(OptionType.STRING, COMMAND_ARGUMENT_NAME,
                "비우면 에코 모드를 켜고 끕니다", false);
        for (KaraokeMode karaokeMode : KaraokeMode.values()) {
            mode.addChoice(karaokeMode.getName(), karaokeMode.name());
        }
        return Commands.slash(COMMAND_NAME, getDescription()).addOptions(mode);
    }

    @Override
    public void onTriggered(SlashCommandInteractionEvent event) {
        Member member = event.getMember();
        VoiceChannel voiceChannel = MemberUtil.getJoinedVoiceChannel(member);
        if (voiceChannel == null) {
            event.replyEmbeds(MusicEmbeds.error(member,
                    "노래방 모드를 설정할 수 없습니다",
                    "노래방 모드로 변경하려면 음성채널에 먼저 참여해주세요").build()).queue();
            return;
        }
        MusicPlayerClient client = Main.getLuffia().getMusicPlayerController().findFromVoiceChannel(voiceChannel);
        if (client == null) {
            event.replyEmbeds(MusicEmbeds.error(member,
                    "노래방 모드를 설정할 수 없습니다",
                    "재생중인 음악이 없어 노래방 모드를 설정할 수 없습니다.").build()).queue();
            return;
        }
        KaraokeMode before = client.getKaraokeMode();
        KaraokeMode after;
        OptionMapping option = event.getOption(COMMAND_ARGUMENT_NAME);
        if (option == null) {
            after = before.isActive() ? KaraokeMode.OFF : KaraokeMode.ECHO;
        } else {
            try {
                after = KaraokeMode.valueOf(option.getAsString());
            } catch (IllegalArgumentException e) {
                event.replyEmbeds(MusicEmbeds.error(member,
                        "노래방 모드를 설정할 수 없습니다",
                        "올바른 모드를 선택해주세요").build()).queue();
                return;
            }
        }
        client.setKaraokeMode(after);
        event.replyEmbeds(new EmbedBuilder()
                .setColor(MusicEmbeds.SUCCESS)
                .setAuthor(MemberUtil.getName(member))
                .setTitle("노래방 모드를 변경했습니다")
                .addField("노래방 모드", before.getName() + " -> " + after.getName(), false)
                .addField("노래 봇", MusicEmbeds.botName(client.getGuild()), false)
                .setFooter(SharedConstant.RELEASE_VERSION)
                .build()
        ).queue();
    }
}
