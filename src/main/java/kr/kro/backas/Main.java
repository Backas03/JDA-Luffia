package kr.kro.backas;

import kr.kro.backas.secret.BotSecret;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.requests.GatewayIntent;
import net.dv8tion.jda.api.utils.ChunkingFilter;
import net.dv8tion.jda.api.utils.MemberCachePolicy;
import net.dv8tion.jda.api.utils.cache.CacheFlag;

import java.io.IOException;

public class Main {
    private static Luffia luffia;

    public static Luffia getLuffia() {
        return luffia;
    }

    public static void main(String[] args) {
        JDABuilder builder = JDABuilder
                .createDefault(BotSecret.TOKEN)
                .setChunkingFilter(ChunkingFilter.ALL) // enable member chunking for all guilds
                .setMemberCachePolicy(MemberCachePolicy.ALL)
                .enableIntents(GatewayIntent.MESSAGE_CONTENT, GatewayIntent.GUILD_MEMBERS)
                .enableCache(CacheFlag.ROLE_TAGS);

        try {
            JDA jda = builder.build().awaitReady();
            luffia = new Luffia(jda);
        } catch (InterruptedException | IOException e) {
            e.printStackTrace();
            System.exit(-1);
        }
    }

    public static boolean isInitialized() {
        return luffia != null;
    }
}