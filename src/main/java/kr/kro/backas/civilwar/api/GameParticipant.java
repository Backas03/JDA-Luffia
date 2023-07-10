package kr.kro.backas.civilwar.api;

public interface GameParticipant<D extends GameUserInfo, T extends GameTeam<?>> {

    T getTeam();

    long getUserId();

    D getUserInfo();
}
