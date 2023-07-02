package kr.kro.backas.command.music;

import kr.kro.backas.Main;
import kr.kro.backas.command.api.CommandSource;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;

public class PlayCommand implements CommandSource {
    @Override
    public void onTriggered(MessageReceivedEvent event) {
        Member member = event.getMember();
        Message message = event.getMessage();
        String arg = this.getArgument(message.getContentRaw().split(SPLIT));
        if (arg == null) {
            boolean b = Main.getLuffia()
                    .getMusicPlayerManager()
                    .getTrackScheduler()
                    .resume();
            if (b) {
                message.reply("일시정지 했던 노래를 다시 재생합니다.").queue();
                return;
            }
            message.reply("검색어를 입력해주세요.").queue();
            return;
        }
        message.reply("\"" + arg + "\" 에 대한 검색 결과를 로딩중입니다... 잠시만 기다려주세요.").queue();
        Main.getLuffia()
                .getMusicPlayerManager()
                .search(member, message, "ytsearch:", arg);
    }

    @Override
    public String getDescription() {
        return "음악을 재생합니다.";
    }

    @Override
    public String getUsage() {
        return "!재생 [검색어 또는 URL]\n!재생 - 일시정지한 곡을 다시 재생합니다.";
    }

    @Override
    public Long[] getAllowedRoleIds() {
        return new Long[0];
    }
}
