package kr.kro.backas.command.music;

import kr.kro.backas.Main;
import kr.kro.backas.SharedConstant;
import kr.kro.backas.command.api.CommandSource;
import kr.kro.backas.music.MusicPlayerClient;
import kr.kro.backas.music.MusicPlayerController;
import kr.kro.backas.util.MemberUtil;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.channel.concrete.VoiceChannel;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;

import java.awt.*;

@Deprecated(forRemoval = true)
public class PauseCommand implements CommandSource {
    @Override
    public void onTriggered(MessageReceivedEvent event) {
        Message message = event.getMessage();
        Member member = event.getMember();
        VoiceChannel voiceChannel = MemberUtil.getJoinedVoiceChannel(member); // 길드에서만 서비스시 member는 null 이 될수 없음
        if (voiceChannel == null) {
            EmbedBuilder builder = new EmbedBuilder()
                    .setColor(Color.decode("#f1554a"))
                    .setAuthor(MemberUtil.getName(member))
                    .setTitle("음악을 일시정지 할 수 없습니다.")
                    .setDescription("음악을 일시정지 하려면 음성채팅방에 먼저 참여해주세요.")
                    .setFooter(SharedConstant.RELEASE_VERSION);
            message.replyEmbeds(builder.build()).queue();
            return;
        }
        MusicPlayerController controller = Main.getLuffia().getMusicPlayerController();
        MusicPlayerClient client = controller.findFromVoiceChannel(voiceChannel);
        if (client == null || !client.hasJoinedToVoiceChannel()) {
            EmbedBuilder builder = new EmbedBuilder()
                    .setColor(Color.decode("#f1554a"))
                    .setAuthor(MemberUtil.getName(member))
                    .setTitle("음악을 일시정지 할 수 없습니다.")
                    .setDescription("현재 재생중인 곡이 없으므로 일시정지 할 수 없습니다.")
                    .setFooter(SharedConstant.RELEASE_VERSION);
            message.replyEmbeds(builder.build()).queue();
            return;
        }
        if (client.pause()) {
            message.reply("현재 재생중인 곡을 일시정지 했습니다.").queue();
            return;
        }
        message.reply("이미 음악이 일시정지 상태입니다!").queue();
    }

    @Override
    public String getDescription() {
        return null;
    }

    @Override
    public String getUsage() {
        return "!일시정지 - 현재 재생중인 곡을 일시정지 합니다.";
    }

    @Override
    public Long[] getAllowedRoleIds() {
        return new Long[0];
    }
}
