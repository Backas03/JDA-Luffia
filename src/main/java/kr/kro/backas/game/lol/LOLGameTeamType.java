package kr.kro.backas.game.lol;

import kr.kro.backas.game.api.GameTeamType;

public enum LOLGameTeamType implements GameTeamType {

    PURPLE("레드 팀"),
    BLUE("블루 팀");

    private final String name;

    LOLGameTeamType(String name) {
        this.name = name;
    }

    @Override
    public String getName() {
        return name;
    }
}
