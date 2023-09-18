package kr.kro.backas;

import com.merakianalytics.orianna.Orianna;
import com.merakianalytics.orianna.types.common.Platform;
import kr.kro.backas.secret.BotSecret;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.requests.GatewayIntent;
import net.dv8tion.jda.api.utils.ChunkingFilter;
import net.dv8tion.jda.api.utils.MemberCachePolicy;
import net.dv8tion.jda.api.utils.cache.CacheFlag;

import java.io.IOException;
import java.time.Duration;
import java.util.Scanner;

public class Main {
    private static Luffia luffia;

    public static Luffia getLuffia() {
        return luffia;
    }

    public static final int SHUTDOWN_TIMEOUT = 5;

    public static void main(String[] args) {
        JDABuilder builder = JDABuilder
                .createDefault(SharedConstant.ON_DEV ? BotSecret.DEV_TOKEN : BotSecret.TOKEN)
                .setChunkingFilter(ChunkingFilter.ALL) // enable member chunking for all guilds
                .setMemberCachePolicy(MemberCachePolicy.ALL)
                .enableIntents(GatewayIntent.MESSAGE_CONTENT, GatewayIntent.GUILD_MEMBERS)
                .enableCache(CacheFlag.ROLE_TAGS);

        try {
            Orianna.setRiotAPIKey(BotSecret.RIOT_API_KEY);
            Orianna.setDefaultPlatform(Platform.KOREA);

            JDA jda = builder.build().awaitReady();
            luffia = new Luffia(jda);
            initScanner();
        } catch (InterruptedException | IOException e) {
            e.printStackTrace();
            System.exit(-1);
        }
    }

    private static void initScanner() {
        /* scanner start */
        while (true) {
            Scanner scanner = new Scanner(System.in);
            try {
                String input = scanner.nextLine();
                if (input.equals("stop")) {
                    if (!isInitialized()) {
                        System.out.println("luffia isn't initialized.");
                        return;
                    }
                    System.out.println("stopping luffia...");
                    JDA discordAPI = luffia.getDiscordAPI();
                    luffia.getMusicPlayerController().shutdownGracefully();
                    if (!discordAPI.awaitShutdown(Duration.ofSeconds(SHUTDOWN_TIMEOUT))) {
                        discordAPI.shutdownNow();
                        discordAPI.awaitShutdown();
                    }
                    System.out.println("...done");
                    System.exit(0);
                }
                System.out.println(input);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
            /* scanner end */
        }
    }

    public static boolean isInitialized() {
        return luffia != null;
    }
}