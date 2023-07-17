package kr.kro.backas.game;

public enum GameType {

    LEAGUE_OF_LEGENDS("리그 오브 레전드"),
    VALORANT("발로란트");


    GameType(String name) {
        this.name = name;
    }

    private final String name;

    public String getName() {
        return name;
    }
}
