package kr.kro.backas.command.music;

import kr.kro.backas.Main;
import kr.kro.backas.command.api.CommandSource;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;

public class SkipCommand implements CommandSource {
    @Override
    public void onTriggered(MessageReceivedEvent event) {
        Message message = event.getMessage();
        Main.getLuffia()
                .getMusicPlayerManager()
                .getTrackScheduler()
                .skip();
        message.reply("현재 재생중인 음악을 스킵하였습니다.").queue();
    }

    @Override
    public String getDescription() {
        return null;
    }

    @Override
    public String getUsage() {
        return "!스킵 - 현재 재생중인 곡을 스킵합니다.";
    }

    @Override
    public Long[] getAllowedRoleIds() {
        return null;
    }
}
