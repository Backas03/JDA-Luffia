package kr.kro.backas.civilwar.api;

import kr.kro.backas.civilwar.GameType;
import net.dv8tion.jda.api.EmbedBuilder;

public interface RecruitmentRoom<T extends GameTeam<? extends GameTeamType>> {
    T getGameTeam(GameTeamType type);

    GameType getGameType();

    EmbedBuilder getInfoMessage();
}
