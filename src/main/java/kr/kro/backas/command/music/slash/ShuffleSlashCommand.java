package kr.kro.backas.command.music.slash;

import kr.kro.backas.BuildInfo;
import kr.kro.backas.Main;
import kr.kro.backas.command.api.SlashCommandSource;
import kr.kro.backas.music.MusicEmbeds;
import kr.kro.backas.music.MusicPlayerClient;
import kr.kro.backas.util.MemberUtil;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.channel.concrete.VoiceChannel;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.interactions.commands.build.SlashCommandData;

public class ShuffleSlashCommand implements SlashCommandSource {
    public static final String COMMAND_NAME = "셔플";
    private static final int MAX_PREVIEW_ROWS = 5;

    @Override
    public SlashCommandData buildCommand() {
        return Commands.slash(COMMAND_NAME, getDescription());
    }

    @Override
    public void onTriggered(SlashCommandInteractionEvent event) {
        Member member = event.getMember();
        VoiceChannel voiceChannel = MemberUtil.getJoinedVoiceChannel(member);
        if (voiceChannel == null) {
            event.replyEmbeds(MusicEmbeds.error(member,
                    "대기열을 섞을 수 없습니다.",
                    "대기열을 섞으려면 음성채팅방에 먼저 참여해주세요.").build()).queue();
            return;
        }
        MusicPlayerClient client = Main.getLuffia().getMusicPlayerController().findFromVoiceChannel(voiceChannel);
        if (client == null) {
            event.replyEmbeds(MusicEmbeds.error(member,
                    "대기열을 섞을 수 없습니다.",
                    "해당 채널에서 음악을 재생중인 노래 봇이 없습니다.").build()).queue();
            return;
        }
        int shuffled = client.shuffleQueue();
        if (shuffled < 2) {
            event.replyEmbeds(MusicEmbeds.error(member,
                    "대기열을 섞을 수 없습니다.",
                    "대기열에 2곡 이상 있어야 섞을 수 있습니다. (현재 " + shuffled + "곡)").build()).queue();
            return;
        }
        event.replyEmbeds(new EmbedBuilder()
                .setColor(MusicEmbeds.SUCCESS)
                .setAuthor(MemberUtil.getName(member))
                .setTitle("대기열을 섞었습니다")
                .setDescription(shuffled + "곡의 순서를 무작위로 바꿨습니다.\n\n**다음 곡**\n"
                        + MusicEmbeds.queuePreview(client.getTrackQueue(), MAX_PREVIEW_ROWS))
                .addField("노래 봇", MusicEmbeds.botName(client.getGuild()), false)
                .setFooter(BuildInfo.VERSION)
                .build()).queue();
    }

    @Override
    public String getDescription() {
        return "대기열의 곡 순서를 무작위로 섞습니다";
    }

    @Override
    public String getUsage() {
        return "/" + COMMAND_NAME;
    }
}
