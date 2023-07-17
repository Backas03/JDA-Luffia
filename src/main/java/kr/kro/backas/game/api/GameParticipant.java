package kr.kro.backas.game.api;

public interface GameParticipant<D extends GameUserInfo, T extends GameTeam<?>> {

    T getTeam();

    long getUserId();

    D getUserInfo();
}
