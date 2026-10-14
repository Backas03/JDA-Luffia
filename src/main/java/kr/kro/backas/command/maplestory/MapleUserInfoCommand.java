package kr.kro.backas.command.maplestory;

import kr.kro.backas.command.api.CommandSource;
import kr.kro.backas.game.maplestory.MapleUserInfo;
import kr.kro.backas.util.StackTraceUtil;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.utils.FileUpload;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URL;
import java.util.concurrent.CompletableFuture;

public class MapleUserInfoCommand implements CommandSource {
    private static final Logger LOGGER = LoggerFactory.getLogger(MapleUserInfoCommand.class);

    @Override
    public void onTriggered(MessageReceivedEvent event) {
        Message message = event.getMessage();
        String input = message.getContentRaw();
        String nickname = this.getArgument(input);
        if (nickname == null) {
            message.reply("닉네임을 입력해주세요.").queue();
            return;
        }
        try {
            MapleUserInfo info = new MapleUserInfo(nickname);
            final Message newer = message.reply("데이터를 조회중에 있습니다 잠시만 기다려주세요...").complete();
            CompletableFuture.supplyAsync(() -> {
                try {
                    newer.editMessageEmbeds(
                            info.getInfoMessage()
                                    .setImage("attachment://character.png")
                                    .build()
                    ).setFiles(
                            FileUpload.fromData(
                                    new URL(info.getUserProfileImageURL()).openStream(),
                                    "character.png"
                            )
                    ).queue();
                } catch (IOException e) {
                    LOGGER.warn("메이플 캐릭터 정보를 전송하지 못했습니다: {}", nickname, e);
                }
                return false;
            });
        } catch (Exception e) {
            StackTraceUtil.replyError("해당 사용자를 찾을 수 없습니다. \"" + nickname + "\"", message, e);
            // message.reply("해당 사용자를 찾을 수 없습니다. \"" + nickname + "\"").queue();
        }
    }

    @Override
    public String getDescription() {
        return null;
    }

    @Override
    public String getUsage() {
        return "!메이플정보 [닉네임]";
    }

    @Override
    public Long[] getAllowedRoleIds() {
        return new Long[0];
    }
}
