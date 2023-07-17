package kr.kro.backas.game.lol;

import kr.kro.backas.game.api.GameParticipant;

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
