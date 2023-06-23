package kr.kro.backas.command;

import kr.kro.backas.Main;
import kr.kro.backas.certification.CertificationManager;
import kr.kro.backas.command.api.CommandSource;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;

public class CertificationCommand implements CommandSource {

    @Override
    public void onTriggered(MessageReceivedEvent event) {
        User user = event.getAuthor();
        Message message = event.getMessage();
        String content = message.getContentRaw();
        String arg = getArgument(content, 0);
        if (arg == null) {
            message.reply("인증할 학교 이메일을 입력해주세요.").queue();
            return;
        }
        CertificationManager certificationManager = Main.getLuffia().getCertificationManager();
        try {
            // 인증 코드 입력
            int code = Integer.parseInt(arg);
            certificationManager.responseCertification(message, user, code);
        } catch (NumberFormatException ignore) {
            // 인증 시작
            certificationManager.requestCertification(arg, message, user);
        }
    }

    @Override
    public String getDescription() {
        return "대구대학교 이메일로 재학생 인증을 진행합니다. 인증 완료로 학교 인증이 필요한 모든 이벤트에 참여하실 수 있습니다";
    }

    @Override
    public String getUsage() {
        return "!인증 [자신의 학교이메일] - 인증 코드를 발급 또는 재발급 받습니다.\n!인증 [6자리 인증 코드] - 인증을 완료합니다.";
    }

    @Override
    public Long[] getAllowedRoleIds() {
        return null;
    }
}
