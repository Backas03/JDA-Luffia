package kr.kro.backas.util;

import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.User;

import java.util.List;

public final class OwnerUtil {
    private static final List<Long> OWNERS = List.of(397589473531002882L);

    public static boolean isOwner(long userId) {
        return OWNERS.contains(userId);
    }

    public static boolean isOwner(User user) {
        return isOwner(user.getIdLong());
    }

    public static boolean isOwner(Member member) {
        return isOwner(member.getUser().getIdLong());
    }

    private OwnerUtil() { throw new UnsupportedOperationException(); }
}
