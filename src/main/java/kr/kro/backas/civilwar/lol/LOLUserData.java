package kr.kro.backas.civilwar.lol;

import com.merakianalytics.orianna.Orianna;
import com.merakianalytics.orianna.types.dto.summoner.Summoner;
import net.dv8tion.jda.api.entities.User;

import java.util.HashMap;
import java.util.Map;

public class LOLUserData {

    private final Map<Long, LOLUserInfo> data = new HashMap<>();

    public LOLUserInfo getUserInfo(long userId) {
        return data.get(userId);
    }

    public LOLUserInfo getUserInfo(User user) {
        return getUserInfo(user.getIdLong());
    }

    public Map<Long, LOLUserInfo> getData() {
        return data;
    }
}
