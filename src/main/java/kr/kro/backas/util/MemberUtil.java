package kr.kro.backas.util;

import kr.kro.backas.Main;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.User;

public final class MemberUtil {
    public static Member getMember(long userId) {
        Guild guild = Main.getLuffia().getPublishedGuild();
        Member member = guild.getMemberById(userId);
        if (member != null) return member;
        return guild.retrieveMemberById(userId).complete();
    }

    public static String getName(Member member) {
        return getName(member.getUser());
    }

    public static String getName(User user) {
        return user.getGlobalName() != null ?
                user.getGlobalName() :
                String.format("%#s", user);
    }

}
