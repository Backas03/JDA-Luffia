package kr.kro.backas.command.music.slash;

import kr.kro.backas.Main;
import kr.kro.backas.SharedConstant;
import kr.kro.backas.command.api.SlashCommandSource;
import kr.kro.backas.music.MusicPlayerClient;
import kr.kro.backas.music.MusicPlayerController;
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

import java.awt.*;

public class SkipSlashCommand implements SlashCommandSource {
    public static final String COMMAND_NAME = "스킵";
    public static final String COUNT_ARGUMENT = "곡수";

    @Override
    public String getDescription() {
        return "현재 재생중인 노래를 스킵합니다";
    }

    @Override
    public String getUsage() {
        return "/" + COMMAND_NAME + " (곡수)";
    }

    @Override
    public SlashCommandData buildCommand() {
        return Commands.slash(COMMAND_NAME, getDescription())
                .addOptions(new OptionData(OptionType.INTEGER, COUNT_ARGUMENT,
                        "건너뛸 곡 수 (기본 1). 현재 곡 포함", false)
                        .setRequiredRange(1, 1000));
    }

    @Override
    public void onTriggered(SlashCommandInteractionEvent event) {
        Member member = event.getMember();
        VoiceChannel voiceChannel = MemberUtil.getJoinedVoiceChannel(member); // 길드에서만 서비스시 member는 null 이 될수 없음
        if (voiceChannel == null) {
            EmbedBuilder builder = new EmbedBuilder()
                    .setColor(Color.decode("#f1554a"))
                    .setAuthor(MemberUtil.getName(member))
                    .setTitle("음악을 건너 뛸 수 없습니다.")
                    .setDescription("음악을 스킵하려면 음성채팅방에 먼저 참여해주세요.")
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
                    .setTitle("음악을 건너 뛸 수 없습니다.")
                    .setDescription("현재 재생중인 곡이 없으므로 스킵할 수 없습니다")
                    .setFooter(SharedConstant.RELEASE_VERSION);
            event.replyEmbeds(builder.build()).queue();
            return;
        }
        OptionMapping countOption = event.getOption(COUNT_ARGUMENT);
        int count = countOption == null ? 1 : Math.max(1, countOption.getAsInt());
        int skipped = client.skip(count);
        event.reply(skipped <= 1 ? "현재 재생중인 음악을 건너뛰었습니다." : skipped + "곡을 건너뛰었습니다.").queue();
    }
}
