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

@Deprecated
public class QuitCommand implements CommandSource {
    @Override
    public void onTriggered(MessageReceivedEvent event) {
        Message message = event.getMessage();
        Member member = event.getMember();
        VoiceChannel voiceChannel = MemberUtil.getJoinedVoiceChannel(member); // 길드에서만 서비스시 member는 null 이 될수 없음
        if (voiceChannel == null) {
            EmbedBuilder builder = new EmbedBuilder()
                    .setColor(Color.decode("#f1554a"))
                    .setAuthor(MemberUtil.getName(member))
                    .setTitle("음성 채팅방과 연결을 끊을 수 없습니다.")
                    .setDescription("해당 명령어를 사용하려면 음성채팅방에 먼저 참여해주세요.")
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
                    .setTitle("음성 채팅방과 연결을 끊을 수 없습니다.")
                    .setDescription("이미 음성채팅방과 연결이 끊어져 있습니다.")
                    .addField("노래 봇", MemberUtil.getName(
                            MemberUtil.getMember(client.getMusicBot().getSelfUser().getIdLong())
                    ), false)
                    .setFooter(SharedConstant.RELEASE_VERSION);
            message.replyEmbeds(builder.build()).queue();
            return;
        }
        client.disconnectFromVoiceChannelAndResetTrack();
        message.reply("전체 음악 재생 대기열이 삭제되었으며, 음성채팅방과 연결을 끊었습니다.").queue();
    }

    @Override
    public String getDescription() {
        return null;
    }

    @Override
    public String getUsage() {
        return "!나가기 - 봇을 음성채팅방에서 퇴장시킵니다.";
    }

    @Override
    public Long[] getAllowedRoleIds() {
        return new Long[0];
    }
}
