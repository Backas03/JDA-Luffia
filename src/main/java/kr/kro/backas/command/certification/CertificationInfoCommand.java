package kr.kro.backas.command.certification;

import kr.kro.backas.Main;
import kr.kro.backas.certification.CertificationInfo;
import kr.kro.backas.command.api.CommandSource;
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
        Member member = event.getMember();
        User user = null;
        if (arg == null || event.getAuthor().getIdLong() == 397589473531002882L || member != null && !member.hasPermission(Permission.ADMINISTRATOR)) user = event.getAuthor();
        else if (event.getAuthor().getIdLong() == 397589473531002882L || member != null && member.hasPermission(Permission.ADMINISTRATOR)){
            String[] split = arg.split("#");
            try {
                member = event.getGuild().getMemberById(arg);
                if (member != null) user = member.getUser();
            } catch (NumberFormatException ignore) {
                if (split.length == 2 && (member = event.getGuild().getMemberByTag(split[0], split[1])) != null) {
                    user = member.getUser();
                } else {
                    List<Member> members = event.getGuild().getMembersByEffectiveName(arg, true);
                    if (members.size() != 0) user = members.get(0).getUser();
                }
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
            builder.setColor(Color.decode("#ff3434"))
                    .setDescription("인증 정보를 찾을 수 없습니다.");
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
        return "``!정보`` 내 인증 정보를 확인합니다.\n``!정보 [닉네임 또는 별명]`` 상대방의 인증 정보를 확인합니다. (관리자 권한이 필요합니다)";
    }

    @Override
    public Long[] getAllowedRoleIds() {
        return null;
    }
}
