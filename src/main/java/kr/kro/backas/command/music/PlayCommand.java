package kr.kro.backas.command.music;

import kr.kro.backas.command.api.CommandSource;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;

public class PlayCommand implements CommandSource {
    @Override
    public void onTriggered(MessageReceivedEvent event) {
        
    }

    @Override
    public String getDescription() {
        return null;
    }

    @Override
    public String getUsage() {
        return null;
    }

    @Override
    public Long[] getAllowedRoleIds() {
        return null;
    }
}
