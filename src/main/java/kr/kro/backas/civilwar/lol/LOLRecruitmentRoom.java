package kr.kro.backas.civilwar.lol;

import kr.kro.backas.Main;
import kr.kro.backas.civilwar.GameType;
import kr.kro.backas.civilwar.api.GameTeamType;
import kr.kro.backas.civilwar.api.RecruitmentRoom;
import kr.kro.backas.util.MemberUtil;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.GuildVoiceState;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.channel.concrete.VoiceChannel;
import net.dv8tion.jda.api.entities.channel.unions.AudioChannelUnion;

import java.util.*;

public class LOLRecruitmentRoom implements RecruitmentRoom<LOLGameTeam> {

    private final LOLGameTeam blueTeam;
    private final LOLGameTeam redTeam;


    private final Queue<Long> joinedMemberIds;

    public LOLRecruitmentRoom() {
        this.joinedMemberIds = new LinkedList<>();
        this.blueTeam = new LOLGameTeam(LOLGameTeamType.BLUE, 1125346490160795658L);
        this.redTeam = new LOLGameTeam(LOLGameTeamType.PURPLE, 1125346517918695514L);
    }

    public boolean join(Member member) {
        return joinedMemberIds.add(member.getIdLong());
    }

    public boolean quit(Member member) {
        return joinedMemberIds.remove(member.getIdLong());
    }

    public Queue<Long> getJoinedMemberIds() {
        return joinedMemberIds;
    }

    public void moveMembers(GameTeamType type) throws IllegalStateException {
        LOLGameTeam team = getGameTeam(type);
        Guild guild = Main.getLuffia().getPublishedGuild();
        VoiceChannel channel = guild.getVoiceChannelById(team.getVoiceChannelId());
        if (channel == null) {
            throw new IllegalStateException("음성 채널을 찾을 수 없습니다. id=" + team.getVoiceChannelId());
        }
        for (LOLParticipant participant : team.getParticipants()) {
            long userId = participant.getUserId();
            Member member = MemberUtil.getMember(userId);
            GuildVoiceState state = member.getVoiceState();
            if (state == null) {
                continue;
            }
            // 음성 채팅방에 참여중인지 확인
            AudioChannelUnion current = state.getChannel();
            if (current == null) {
                continue;
            }
            guild.moveVoiceMember(member, channel).queue();
        }
    }

    public void autoDeploy() {
        // TODO: weight에 따라 동일 분배
    }

    public void moveAllMembers() throws IllegalStateException {
        moveMembers(LOLGameTeamType.BLUE);
        moveMembers(LOLGameTeamType.PURPLE);
    }

    @Override
    public LOLGameTeam getGameTeam(GameTeamType type) {
        if (type == LOLGameTeamType.PURPLE) return redTeam;
        if (type == LOLGameTeamType.BLUE) return blueTeam;
        return null;
    }

    @Override
    public GameType getGameType() {
        return GameType.LEAGUE_OF_LEGENDS;
    }

    @Override
    public EmbedBuilder getInfoMessage() {
        return null;
    }
}
