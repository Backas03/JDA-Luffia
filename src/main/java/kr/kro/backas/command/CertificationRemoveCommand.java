package kr.kro.backas.command;

import kr.kro.backas.Main;
import kr.kro.backas.certification.CertificationInfo;
import kr.kro.backas.certification.CertificationManager;
import kr.kro.backas.command.api.CommandSource;
import kr.kro.backas.util.StackTraceUtil;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;

import java.io.IOException;

public class CertificationRemoveCommand implements CommandSource {

    @Override
    public void onTriggered(MessageReceivedEvent event) {
        String arg = getArgument(event.getMessage().getContentRaw(), 0);
        try {
            long userId = Long.parseLong(arg);
            CertificationManager manager = Main.getLuffia().getCertificationManager();
            CertificationInfo info = manager
                    .getCertificationData()
                    .getCertificationInfo(userId);
            if (info == null) {
                event.getMessage().reply("해당 유저의 인증 데이터를 찾을 수 없습니다.").queue();
                return;
            }
            manager.removeCertification(userId);
            event.getMessage().reply("해당 유저의 인증 데이터가 삭제 완료 되었습니다.").queue();
        } catch (NumberFormatException ignore) {
            event.getMessage().reply("유저 아이디를 찾을 수 없습니다.").queue();
        } catch (IOException e) {
            event.getMessage().reply("해당 데이터 처리중 오류가 발생했습니다.\n" + StackTraceUtil.convertDiscord(e)).queue();
        }
    }

    @Override
    public String getDescription() {
        return null;
    }

    @Override
    public String getUsage() {
        return "``!인증해제 [userId]`` 특정 유저의 인증 데이터를 삭제합니다.";
    }

    @Override
    public Long[] getAllowedRoleIds() {
        return new Long[]{ 1068859037455749181L, 1120389710573994106L };
    }
}
