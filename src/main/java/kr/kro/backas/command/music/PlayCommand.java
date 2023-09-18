package kr.kro.backas.command.music;

import kr.kro.backas.Main;
import kr.kro.backas.command.api.CommandSource;
import kr.kro.backas.music.Identifier;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;

public class PlayCommand implements CommandSource {
    @Override
    public void onTriggered(MessageReceivedEvent event) {
        Message message = event.getMessage();
        String arg = this.getArgument(message.getContentRaw().split(SPLIT));
        if (arg == null) {
            message.reply("검색어를 입력해주세요").queue();
            return;
        }
        Main.getLuffia()
                .getMusicPlayerController()
                .search(Identifier.YOUTUBE, arg, message);
    }

    @Override
    public String getDescription() {
        return "음악을 재생합니다.";
    }

    @Override
    public String getUsage() {
        return "!재생 [검색어 또는 URL]";
    }

    @Override
    public Long[] getAllowedRoleIds() {
        return new Long[0];
    }
}
