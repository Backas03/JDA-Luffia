package kr.kro.backas.command.music;

import kr.kro.backas.Main;
import kr.kro.backas.command.api.CommandSource;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;

public class QuitCommand implements CommandSource {
    @Override
    public void onTriggered(MessageReceivedEvent event) {
        Message message = event.getMessage();
        Main.getLuffia()
                .getMusicPlayerManager()
                .getTrackScheduler()
                .quit();
        message.reply("음성 채팅방 연결을 끊었습니다.").queue();
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
