package kr.kro.backas.util;

import kr.kro.backas.Main;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.GuildVoiceState;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.entities.channel.concrete.VoiceChannel;
import net.dv8tion.jda.api.entities.channel.unions.AudioChannelUnion;

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

    public static boolean isInVoiceChannel(Member member) {
        GuildVoiceState state = member.getVoiceState();
        return state != null && state.inAudioChannel();
    }

    public static AudioChannelUnion getJoinedAudioChannel(Member member) {
        GuildVoiceState state = member.getVoiceState();
        if (state == null || !state.inAudioChannel()) return null;
        return state.getChannel();
    }

    public static VoiceChannel getJoinedVoiceChannel(Member member) {
        GuildVoiceState state = member.getVoiceState();
        if (state == null || !state.inAudioChannel() || state.getChannel() == null) return null;
        return state.getChannel().asVoiceChannel();
    }
}
