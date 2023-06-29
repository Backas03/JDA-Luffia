package kr.kro.backas.command;

import kr.kro.backas.Main;
import kr.kro.backas.SharedConstant;
import kr.kro.backas.certification.CertificationInfo;
import kr.kro.backas.certification.CertificationManager;
import kr.kro.backas.command.api.CommandSource;
import kr.kro.backas.util.StackTraceUtil;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;

import java.io.IOException;

public class ForceCertificationCommand implements CommandSource {
    @Override
    public void onTriggered(MessageReceivedEvent event) {
        Message message = event.getMessage();
        String input = message.getContentRaw();
        String arg1 = this.getArgument(input, 0);
        String arg2 = this.getArgument(input, 1);
        if (arg1 == null) {
            message.reply("인증할 이메일을 입력해주세요.").queue();
            return;
        }
        CertificationManager manager = Main.getLuffia().getCertificationManager();
        if (!manager.isValidEmail(arg1)) {
            message.reply("이메일 형식을 다시 확인해주세요.").queue();
            return;
        }
        if (arg2 == null) {
            message.reply("userId를 입력해주세요.").queue();
            return;
        }
        try {
            long userId = Long.parseLong(arg2);
            if (manager.getCertificationData().isCertificated(userId)) {
                message.reply("이미 해당유저는 인증이 완료된 상태입니다.").queue();
                return;
            }
            Member member = Main.getLuffia()
                    .getDiscordAPI()
                    .getGuildById(SharedConstant.MAIN_GUILD_ID)
                    .getMemberById(userId);
            if (member == null) {
                message.reply("유저를 찾을수 없습니다.").queue();
                return;
            }
            manager.certificate(userId, CertificationInfo.email(arg1));
            message.reply("해당 유저를 인증 상태로 전환하였습니다.").queue();
        } catch (NumberFormatException ignore) {
            message.reply("userId는 정수여야합니다.").queue();
        } catch (IOException e) {
            StackTraceUtil.replyError("해당 데이터 처리중 오류가 발생했습니다.", message, e);
        }
    }

    @Override
    public String getDescription() {
        return "유저를 해당 이메일로 강제 인증합니다.";
    }

    @Override
    public String getUsage() {
        return "!강제인증 [대구대학교 이메일] [userId]";
    }

    @Override
    public Long[] getAllowedRoleIds() {
        return new Long[] { 1115667594503012462L };
    }
}
