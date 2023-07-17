package kr.kro.backas.game.api;

public interface GameTeam<T extends GameTeamType> {

    String getName();

    T getTeamType();

}
