package kr.kro.backas.civilwar.lol;

import com.merakianalytics.orianna.Orianna;
import com.merakianalytics.orianna.types.common.Queue;
import com.merakianalytics.orianna.types.common.Tier;
import com.merakianalytics.orianna.types.core.league.LeagueEntry;
import com.merakianalytics.orianna.types.core.summoner.Summoner;
import kr.kro.backas.civilwar.api.GameUserInfo;

public class LOLUserInfo implements GameUserInfo {

    public String nickname;
    private int weight;

    public LOLUserInfo(String nickname, int weight) {
        this.nickname = nickname;
        this.weight = weight;
    }

    public void setWeight(int weight) {
        this.weight = weight;
    }

    public Summoner getSummoner() {
        return Orianna.summonerNamed(nickname).get();
    }

    public String getTier(Queue queue) {
        Summoner summoner = getSummoner();
        if (summoner == null) return "";
        LeagueEntry position = summoner.getLeaguePosition(queue);
        if (position == null) return "언랭";
        return position.getTier() + " " + position.getDivision() + " " + position.getLeaguePoints() + "점";
    }

    @Override
    public int getWeight() {
        return weight;
    }
}
