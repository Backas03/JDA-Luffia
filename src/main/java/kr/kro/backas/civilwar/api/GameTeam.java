package kr.kro.backas.civilwar.api;

public interface GameTeam<T extends GameTeamType> {

    String getName();

    T getTeamType();

}
