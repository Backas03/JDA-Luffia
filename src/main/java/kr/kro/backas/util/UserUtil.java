package kr.kro.backas.util;

import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.User;

public final class UserUtil {

    public static String getName(Member member) {
        return getName(member.getUser());
    }

    public static String getName(User user) {
        return user.getGlobalName() != null ?
                user.getGlobalName() :
                String.format("%#s", user);
    }
}
