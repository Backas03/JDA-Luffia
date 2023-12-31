package kr.kro.backas.command.music;

import kr.kro.backas.Main;
import kr.kro.backas.SharedConstant;
import kr.kro.backas.command.api.CommandSource;
import kr.kro.backas.music.MusicPlayerClient;
import kr.kro.backas.music.MusicPlayerController;
import kr.kro.backas.music.RepeatMode;
import kr.kro.backas.util.MemberUtil;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.channel.concrete.VoiceChannel;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;

import java.awt.*;

@Deprecated(forRemoval = true)
public class RepeatCurrentCommand implements CommandSource {
    @Override
    public void onTriggered(MessageReceivedEvent event) {
        RepeatMode mode = RepeatMode.REPEAT_CURRENT;
        Message message = event.getMessage();
        Member member = event.getMember();
        VoiceChannel voiceChannel = MemberUtil.getJoinedVoiceChannel(member); // 길드에서만 서비스시 member는 null 이 될수 없음
        if (voiceChannel == null) {
            EmbedBuilder builder = new EmbedBuilder()
                    .setColor(Color.decode("#f1554a"))
                    .setAuthor(MemberUtil.getName(member))
                    .setTitle("반복재생 모드를 설정할 수 없습니다.")
                    .setDescription("반복재생 모드를 설정하려면 음성채팅방에 먼저 참여해주세요.")
                    .setFooter(SharedConstant.RELEASE_VERSION);
            message.replyEmbeds(builder.build()).queue();
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
            message.replyEmbeds(builder.build()).queue();
            return;
        }
        client.setRepeatMode(mode);
        message.reply("반복 모드를 현재 곡 반복으로 설정했습니다.").queue();
    }

    @Override
    public String getDescription() {
        return null;
    }

    @Override
    public String getUsage() {
        return "!반복 - 현재 재생중인 곡을 반복합니다.";
    }

    @Override
    public Long[] getAllowedRoleIds() {
        return new Long[0];
    }
}
