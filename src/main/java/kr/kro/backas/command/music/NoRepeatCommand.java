package kr.kro.backas.command.music;

import kr.kro.backas.Main;
import kr.kro.backas.command.api.CommandSource;
import kr.kro.backas.music.TrackScheduler;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;

public class NoRepeatCommand implements CommandSource {
    @Override
    public void onTriggered(MessageReceivedEvent event) {
        int mode = TrackScheduler.RepeatMode.NO_REPEAT;
        Message message = event.getMessage();
        Main.getLuffia()
                .getMusicPlayerManager()
                .getTrackScheduler()
                .setRepeatMode(mode);
        message.reply("반복 모드를 해제하였습니다.").queue();
    }

    @Override
    public String getDescription() {
        return null;
    }

    @Override
    public String getUsage() {
        return "!반복해제 - 반복 모드를 해제합니다.";
    }

    @Override
    public Long[] getAllowedRoleIds() {
        return new Long[0];
    }
}
