package kr.kro.backas.game.lol;

import com.merakianalytics.orianna.Orianna;
import com.merakianalytics.orianna.types.common.Queue;
import com.merakianalytics.orianna.types.core.account.Account;
import com.merakianalytics.orianna.types.core.championmastery.ChampionMasteries;
import com.merakianalytics.orianna.types.core.championmastery.ChampionMastery;
import com.merakianalytics.orianna.types.core.league.LeagueEntry;
import com.merakianalytics.orianna.types.core.summoner.Summoner;
import kr.kro.backas.game.api.GameUserInfo;
import kr.kro.backas.util.DurationUtil;
import net.dv8tion.jda.api.EmbedBuilder;
import org.joda.time.DateTime;
import org.joda.time.Duration;

import java.awt.*;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

public class LOLUserInfo implements GameUserInfo {

    public String nickname;
    public String tag;

    public LOLUserInfo(String nickname, String tag) throws IllegalArgumentException {
        this.nickname = nickname;
        this.tag = tag;
    }

    public boolean exists() {
        return getAccount().exists();
    }

    public Account getAccount() {
        return Orianna.accountWithRiotId(nickname, tag).get();
    }

    public Summoner getSummoner() {
        return Summoner.withPuuid(getAccount().getPuuid()).get();
    }

    public EmbedBuilder getInfoMessage() {
        Summoner summoner = getSummoner();
        String imageURL = summoner
                .getProfileIcon()
                .getImage()
                .getURL();

        EmbedBuilder builder = new EmbedBuilder();
        builder.setColor(Color.decode("#ff8c00"));
        builder.setAuthor(
                "Lv." + summoner.getLevel() + "    " + nickname + "  #" + tag + "",
                "https://www.op.gg/summoners/kr/" + URLEncoder.encode(nickname, StandardCharsets.UTF_8),
                imageURL
        );
        builder.addField("개인/듀오 랭크", getTier(Queue.RANKED_SOLO), true);
        builder.addField("자유 랭크", getTier(Queue.RANKED_FLEX), true);

        builder.addField("", "", false);

        String encodedNickname = URLEncoder.encode(nickname, StandardCharsets.UTF_8);

        ChampionMasteries masteries = Orianna.championMasteriesForSummoner(summoner).get();
        for (int i=0; i<Math.min(3, masteries.size()); i++) {
            ChampionMastery mastery = masteries.get(i);
            if (mastery == null || !mastery.exists()) {
                continue;
            }
            builder.addField(
                    (i + 1) + ". " + mastery.getChampion().getName(),
                    "숙련도: " + mastery.getLevel() + " 레벨\n" +
                    "숙련도 포인트: " + mastery.getPoints() + "\n",
                    true
            );
        }
        builder.addField("전적 검색 사이트로 이동하기",
                "[오피지지](https://www.op.gg/summoners/kr/" + encodedNickname + ")\n" +
                        "[롤PS](https://lol.ps/summoner/" + encodedNickname + "?region=kr)\n" +
                        "[포우](https://fow.kr/find/" + encodedNickname + ")",
                false
        );

        DateTime now = DateTime.now();
        DateTime updated = summoner.getUpdated();

        Duration diff = new Duration(updated, now);

        builder.setFooter(DurationUtil.parseDuration(diff) + " 전에 업데이트 되었습니다.");
        return builder;
    }

    public String getTier(Queue queue) {
        Summoner summoner = getSummoner();
        if (summoner == null) return "";
        LeagueEntry position = summoner.getLeaguePosition(queue);
        if (position == null) return "언랭";
        return position.getTier() + " " + position.getDivision() + "  " + position.getLeaguePoints() + "점";
    }

    @Override
    public int getWeight() {
        return 0;
    }
}
