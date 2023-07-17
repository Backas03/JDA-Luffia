package kr.kro.backas.command.lol;

import kr.kro.backas.civilwar.lol.LOLUserInfo;
import kr.kro.backas.command.api.CommandSource;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;

import java.util.concurrent.CompletableFuture;

public class LOLUserInfoCommand implements CommandSource {
    @Override
    public void onTriggered(MessageReceivedEvent event) {
        Message message = event.getMessage();
        String input = message.getContentRaw();
        String nickname = this.getArgument(input);
        if (nickname == null) {
            message.reply("닉네임을 입력해주세요.").queue();
            return;
        }
        LOLUserInfo info = new LOLUserInfo(nickname);
        if (!info.exists()) {
            message.reply("해당 사용자를 찾을 수 없습니다.").queue();
            return;
        }
        final Message newer = message.reply("데이터를 조회중에 있습니다 잠시만 기다려주세요...").complete();
        CompletableFuture.supplyAsync(() -> {
            newer.editMessageEmbeds(info.getInfoMessage().build()).queue();
            return false;
        });
    }

    @Override
    public String getDescription() {
        return null;
    }

    @Override
    public String getUsage() {
        return "!롤정보 [닉네임]";
    }

    @Override
    public Long[] getAllowedRoleIds() {
        return new Long[0];
    }
}
