package kr.kro.backas.civilwar.lol;

import kr.kro.backas.civilwar.api.GameParticipant;
import net.dv8tion.jda.api.entities.Member;

public class LOLParticipant implements GameParticipant<LOLUserInfo, LOLGameTeam> {

    private final long userId;
    private final LOLGameTeam team;

    public LOLParticipant(long userId, LOLGameTeam team) {
        this.userId = userId;
        this.team = team;
    }

    @Override
    public LOLGameTeam getTeam() {
        return team;
    }

    @Override
    public long getUserId() {
        return userId;
    }

    @Override
    public LOLUserInfo getUserInfo() {
        return null;
    }
}
