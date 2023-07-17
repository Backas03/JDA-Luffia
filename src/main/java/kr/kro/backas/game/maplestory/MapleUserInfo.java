package kr.kro.backas.game.maplestory;

import net.dv8tion.jda.api.EmbedBuilder;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import java.awt.*;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

public class MapleUserInfo {

    public static final String MAPLE_GG_BASE_URL = "https://maple.gg/";

    private final String nickname;
    private String userProfileImageURL;
    private String worldImageURL;
    private String world;
    private String job;
    private String levelData;
    private String popularity;
    private String guild;
    private String totalRank;
    private String worldRank;
    private String jobRankWorld;
    private String jobRankTotal;
    private String mureungFloor;
    private String mureungWorldRank;
    private String mureungRank;
    private String mureungInfo;
    private String mureungDate;
    private String mureungTime;
    private String unionName;
    private String unionWorldRank;
    private String unionRank;
    private String unionLevel;
    private String unionImageURL;
    private String unionDate;
    private String unionPower;
    private String lastUpdate;
    private String lastActiveDate;

    private final Map<String, String> codies;

    public enum Cody {
        HAT("모자"),
        HAIR("헤어"),
        EYES("성형"),
        SHIRT("상의"),
        PANTS("하의"),
        SHOES("신발"),
        WEAPON("무기");

        private final String name;

        public String getName() {
            return name;
        }

        Cody(String name) {
            this.name = name;
        }
    }


    public MapleUserInfo(String nickname) throws IOException {
        this.nickname = nickname;
        this.codies = new HashMap<>();
        update();
    }

    public String getNicknameURLParsed() {
        return URLEncoder.encode(nickname, StandardCharsets.UTF_8);
    }

    public String getMapleGGPage() {
        return MAPLE_GG_BASE_URL + "u/" + getNicknameURLParsed();
    }

    public void update() throws IOException {
        Document doc = Jsoup.connect(getMapleGGPage()).get();
        userProfileImageURL = doc
                .selectFirst(".character-image")
                .attr("src");
        worldImageURL = "https:" + doc
                .selectFirst(".align-middle")
                .attr("src");
        world = doc
                .selectFirst(".align-middle")
                .attr("alt");
        levelData = doc
                .selectFirst("li.user-summary-item")
                .text();
        job = doc
                .select("li.user-summary-item")
                .get(1)
                .text();
        popularity = doc
                .select("li.user-summary-item")
                .get(2)
                .select("span")
                .get(1)
                .text();
        try {
            guild = doc
                    .selectFirst("a.text-yellow.text-underline")
                    .text();
        } catch (Exception ignore) {
            guild = "없음";
        }
        totalRank = doc
                .selectFirst("div.col-lg-2.col-md-4.col-sm-4.col-6.mt-3")
                .select("span")
                .first()
                .text();
        worldRank = doc
                .selectFirst("div.col-lg-2.col-md-4.col-sm-4.col-6.mt-3")
                .select("span")
                .first()
                .text();
        jobRankWorld = doc
                .selectFirst("div.col-lg-2.col-md-4.col-sm-4.col-6.mt-3")
                .select("span")
                .first()
                .text();
        jobRankTotal = doc
                .selectFirst("div.col-lg-2.col-md-4.col-sm-4.col-6.mt-3")
                .select("span")
                .first()
                .text();
        for (Element itemElement : doc
                .selectFirst("div.character-coord__items")
                .select("div.character-coord__item")) {
            String type = itemElement.selectFirst("span.character-coord__item-type").text();
            String name = itemElement.selectFirst("span.character-coord__item-name").text();

            codies.put(type, name);
        }
        try {
            mureungFloor = doc
                    .selectFirst("h1.user-summary-floor")
                    .text()
                    .trim();
            mureungInfo = doc
                    .selectFirst("footer.user-summary-box-footer")
                    .selectFirst("div.d-block > span")
                    .text()
                    .trim();
            mureungWorldRank = doc
                    .selectFirst("footer.user-summary-box-footer")
                    .selectFirst("div.mb-2 > b:contains(월드랭킹) + span")
                    .text()
                    .trim();
            mureungRank = doc
                    .selectFirst("footer.user-summary-box-footer")
                    .select("div.mb-2 > b:contains(랭킹) + span")
                    .get(1)
                    .text()
                    .trim();
            mureungDate = doc
                    .selectFirst("footer.user-summary-box-footer")
                    .selectFirst("div.user-summary-date > span")
                    .text()
                    .trim();
            mureungTime = doc
                    .selectFirst("small.user-summary-duration")
                    .text();
        } catch (Exception ignore) {
            mureungFloor = "기록이 없습니다.";
            mureungInfo = "기록이 없습니다.";
            mureungWorldRank = "기록이 없습니다.";
            mureungRank = "기록이 없습니다.";
            mureungDate = "기록이 없습니다.";
            mureungTime = "기록이 없습니다.";
        }

        try {
            unionName = doc
                    .selectFirst("div.user-summary-tier-string")
                    .text();
            unionLevel = doc
                    .selectFirst("span.user-summary-level")
                    .text();
            unionImageURL = "https:" + doc
                    .selectFirst("img.user-summary-tier")
                    .attr("src");

            unionPower = doc
                    .select("div.d-block span")
                    .get(2)
                    .text();
            unionWorldRank = doc
                    .selectFirst("div.mb-2 span")
                    .text();
            unionRank = doc
                    .select("div.mb-2 span")
                    .last()
                    .text();
            unionDate = doc
                    .select("div.user-summary-date span")
                    .last()
                    .text()
                    .replace("기준일: ", "");
        } catch (Exception ignore) {
            unionName = "기록이 없습니다.";
            unionLevel = "기록이 없습니다.";
            unionImageURL = "기록이 없습니다.";
            unionPower = "기록이 없습니다.";
            unionWorldRank = "기록이 없습니다.";
            unionRank = "기록이 없습니다.";
            unionDate = "기록이 없습니다.";
        }
        lastUpdate = doc
                .selectFirst("span.d-block.font-weight-light")
                .text();
        lastActiveDate =  doc
                .selectFirst("div.mb-1.text-white span.font-size-12.text-white")
                .text();
    }

    public EmbedBuilder getInfoMessage() {
        EmbedBuilder builder = new EmbedBuilder()
                .setColor(Color.decode("#d38e43"))
                .setAuthor(nickname + " (maple.gg 페이지로 이동) ", getMapleGGPage(), worldImageURL)
                .setDescription(
                        "서버: " + world +
                        "\n소속길드: " + guild +
                        "\n\n" + job +
                        "\n" + levelData +
                        "\n인기도 " + popularity
                ).addField(
                        "랭크 정보",
                        "종합랭킹 " + totalRank + "\n" +
                                "월드랭킹 " + worldRank + "\n\n" +
                                "직업랭킹(월드) " + jobRankWorld + "\n" +
                                "직업랭킹(전체) " + jobRankTotal
                        ,true
                ).addField(
                        "무릉도장",
                        mureungFloor + "\n" +
                                "월드랭킹 " + mureungWorldRank + "\n" +
                                "랭킹 " + mureungRank + "\n" +
                                mureungTime + "\n" +
                                mureungDate,
                        true
                ).addField(
                        "유니온 [" + unionRank + "]",
                        "레벨 " + unionLevel +
                                "\n " + unionPower +
                                "\n월드랭킹 " + unionWorldRank +
                                "\n랭킹 " + unionRank +
                                "\n기준일 " + unionDate,
                        true
                ).addField("코디 정보",
                        "모자: " + getCody(Cody.HAT) +
                        "\n헤어: " + getCody(Cody.HAIR) +
                        "\n성형: " + getCody(Cody.EYES) +
                        "\n상의: " + getCody(Cody.SHIRT) +
                        "\n하의: " + getCody(Cody.PANTS) +
                        "\n신발: " + getCody(Cody.SHOES) +
                        "\n무기: " + getCody(Cody.WEAPON), false
                ).setFooter(
                        lastActiveDate + "\n" +
                        lastUpdate +
                        "\n\n해당 기능은 베타 테스트 중이며 실제 값과 오차가 있을수 있습니다."
                );
        return builder;
    }

    public String getLastUpdate() {
        return lastUpdate;
    }

    public String getLastActiveDate() {
        return lastActiveDate;
    }

    public String getNickname() {
        return nickname;
    }

    public String getUserProfileImageURL() {
        return userProfileImageURL;
    }

    public String getWorldImageURL() {
        return worldImageURL;
    }

    public String getWorld() {
        return world;
    }

    public String getJob() {
        return job;
    }

    public String getLevelData() {
        return levelData;
    }

    public String getPopularity() {
        return popularity;
    }

    public String getGuild() {
        return guild;
    }

    public String getTotalRank() {
        return totalRank;
    }

    public String getWorldRank() {
        return worldRank;
    }

    public String getJobRankWorld() {
        return jobRankWorld;
    }

    public String getJobRankTotal() {
        return jobRankTotal;
    }

    public Map<String, String> getCodies() {
        return codies;
    }

    public String getCody(Cody cody) {
        return codies.get(cody.getName());
    }

    public String getMureungFloor() {
        return mureungFloor;
    }

    public String getMureungWorldRank() {
        return mureungWorldRank;
    }

    public String getMureungRank() {
        return mureungRank;
    }

    public String getMureungInfo() {
        return mureungInfo;
    }

    public String getMureungDate() {
        return mureungDate;
    }

    public String getMureungTime() {
        return mureungTime;
    }

    public String getUnionName() {
        return unionName;
    }

    public String getUnionWorldRank() {
        return unionWorldRank;
    }

    public String getUnionRank() {
        return unionRank;
    }

    public String getUnionLevel() {
        return unionLevel;
    }

    public String getUnionImageURL() {
        return unionImageURL;
    }

    public String getUnionDate() {
        return unionDate;
    }

    public String getUnionPower() {
        return unionPower;
    }
}
