package kr.kro.backas.command.certification;

import kr.kro.backas.Main;
import kr.kro.backas.certification.CertificationInfo;
import kr.kro.backas.command.api.CommandSource;
import kr.kro.backas.util.MemberUtil;
import kr.kro.backas.util.OwnerUtil;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;

import java.awt.*;
import java.text.SimpleDateFormat;
import java.time.Instant;
import java.util.Date;
import java.util.List;

public class CertificationInfoCommand implements CommandSource {

    @Override
    public void onTriggered(MessageReceivedEvent event) {
        String arg = getArgument(event.getMessage().getContentRaw(), 0);
        Member member = event.getMember(); // webhook message || private message 일시 null
        User user;
        if (arg == null) user = event.getAuthor();
        else {
            if (OwnerUtil.isOwner(397589473531002882L) || member.hasPermission(Permission.ADMINISTRATOR)) {
                try {
                    Member temp = MemberUtil.getMember(Long.parseLong(arg));
                    user = temp != null ? temp.getUser() : null;
                } catch (NumberFormatException ignore) {
                    event.getMessage().reply("올바른 user id 를 입력해주세요.").queue();
                    return;
                }
            } else {
                EmbedBuilder builder = new EmbedBuilder();
                builder.setColor(Color.decode("#ff3434"))
                        .setDescription("해당 명령어를 실행할 권한이 없습니다.");
                event.getMessage().replyEmbeds(builder.build()).queue();
                return;
            }
        }
        if (user == null) {
            event.getMessage().reply("길드 내에서 해당 유저를 찾을 수 없습니다.").queue();
            return;
        }
        CertificationInfo info = Main.getLuffia()
                .getCertificationManager()
                .getCertificationData()
                .getCertificationInfo(user);

        String url = user.getAvatarUrl() != null ? user.getAvatarUrl() : user.getDefaultAvatarUrl();

        EmbedBuilder builder = new EmbedBuilder()
                .setTitle(user.getName() + " 님의 인증 정보")
                .setAuthor(user.getGlobalName(), url, url);
        if (info == null) {
            builder = new EmbedBuilder();
            builder.setColor(Color.decode("#ff3434"))
                    .setDescription("인증 정보를 찾을 수 없습니다.");
            event.getMessage().replyEmbeds(builder.build()).queue();
            return;
        }

        builder.setColor(Color.decode("#8513ff"))
                .addField("이메일", info.email(), true)
                .addField(
                        "인증 날짜",
                        new SimpleDateFormat("yyyy-MM-dd hh시 mm분 ss.SSS초")
                                .format(Date.from(Instant.ofEpochMilli(info.date()))),
                        true)
                .addField("유저 아이디", user.getId(), false)
                .addField("알려진 이름", info.knownAs(), false);

        event.getMessage().replyEmbeds(builder.build()).queue();
    }

    @Override
    public String getDescription() {
        return """ 
                * 인증 정보를 확인합니다.""";
    }

    @Override
    public String getUsage() {
        return "``!인증정보`` 내 인증 정보를 확인합니다.\n``!인증정보 [닉네임 또는 별명]`` 상대방의 인증 정보를 확인합니다. (어드민 권한이 필요합니다)";
    }

    @Override
    public Long[] getAllowedRoleIds() {
        return null;
    }
}
