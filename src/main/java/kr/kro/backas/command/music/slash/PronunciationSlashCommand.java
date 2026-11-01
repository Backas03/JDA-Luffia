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

public class PronunciationSlashCommand implements SlashCommandSource {
    public static final String COMMAND_NAME = "가사발음";

    @Override
    public String getDescription() {
        return "실시간 일본어 가사 아래에 한국어 발음을 보여 주는 기능을 켜고 끕니다";
    }

    @Override
    public String getUsage() {
        return "/" + COMMAND_NAME;
    }

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
                    "가사 발음을 설정할 수 없습니다",
                    "가사 발음을 켜거나 끄려면 음성채널에 먼저 참여해주세요").build()).queue();
            return;
        }
        MusicPlayerClient client = Main.getLuffia().getMusicPlayerController().findFromVoiceChannel(voiceChannel);
        if (client == null) {
            event.replyEmbeds(MusicEmbeds.error(member,
                    "가사 발음을 설정할 수 없습니다",
                    "재생중인 음악이 없어 가사 발음을 설정할 수 없습니다.").build()).queue();
            return;
        }
        boolean showing = !client.isShowingPronunciation();
        client.setShowingPronunciation(showing);
        event.replyEmbeds(new EmbedBuilder()
                .setColor(MusicEmbeds.SUCCESS)
                .setAuthor(MemberUtil.getName(member))
                .setTitle(showing ? "가사 발음을 켰습니다" : "가사 발음을 껐습니다")
                .setDescription(showing ? "실시간 가사에서 일본어 줄 아래에 한국어 발음이 표시됩니다." : null)
                .addField("노래 봇", MusicEmbeds.botName(client.getGuild()), false)
                .setFooter(BuildInfo.VERSION)
                .build()
        ).queue();
    }
}
