package kr.kro.backas.util;

import kr.kro.backas.Main;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;

public final class MemberUtil {
    public static Member getMember(long userId) {
        Guild guild = Main.getLuffia().getPublishedGuild();
        Member member = guild.getMemberById(userId);
        if (member != null) return member;
        return guild.retrieveMemberById(userId).complete();
    }

}
