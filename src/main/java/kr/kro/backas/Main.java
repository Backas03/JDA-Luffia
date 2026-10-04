package kr.kro.backas;

import club.minnced.discord.jdave.interop.JDaveSessionFactory;
import com.merakianalytics.orianna.Orianna;
import com.merakianalytics.orianna.types.common.Platform;
import kr.kro.backas.secret.BotSecret;
import kr.kro.backas.util.BotShutdown;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.audio.AudioModuleConfig;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.requests.GatewayIntent;
import net.dv8tion.jda.api.utils.ChunkingFilter;
import net.dv8tion.jda.api.utils.MemberCachePolicy;
import net.dv8tion.jda.api.utils.cache.CacheFlag;
import net.dv8tion.jda.api.utils.messages.MessageRequest;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.EnumSet;
import java.util.List;
import java.util.Scanner;

public class Main {
    private static Luffia luffia;

    public static Luffia getLuffia() {
        return luffia;
    }

    static final long SHUTDOWN_HALT_AFTER_MS = 10_000;

    public static void main(String[] args) {
        Runtime.getRuntime().addShutdownHook(new Thread(Main::stop, "luffia-shutdown"));
        MessageRequest.setDefaultMentions(EnumSet.noneOf(Message.MentionType.class));
        MessageRequest.setDefaultMentionRepliedUser(false);
        JDABuilder builder = JDABuilder
                .createDefault(SharedConstant.ON_DEV ? BotSecret.DEV_TOKEN : BotSecret.TOKEN)
                .setChunkingFilter(ChunkingFilter.ALL) // enable member chunking for all guilds
                .setMemberCachePolicy(MemberCachePolicy.ALL)
                .enableIntents(GatewayIntent.MESSAGE_CONTENT, GatewayIntent.GUILD_MEMBERS)
                .enableCache(CacheFlag.ROLE_TAGS)
                .setAudioModuleConfig(new AudioModuleConfig().withDaveSessionFactory(new JDaveSessionFactory()));

        try {
            Orianna.setRiotAPIKey(BotSecret.RIOT_API_KEY);
            Orianna.setDefaultPlatform(Platform.KOREA);

            JDA jda = builder.build().awaitReady();
            luffia = new Luffia(jda);
            // initScanner();
        } catch (InterruptedException | IOException e) {
            e.printStackTrace();
            System.exit(-1);
        }
    }

    private static void stop() {
        startHaltWatchdog();
        System.out.println("stopping luffia...");
        Luffia current = luffia;
        if (current == null) return;
        try {
            current.getMusicPlayerController().shutdownGracefully();
        } catch (RuntimeException e) {
            LoggerFactory.getLogger("Luffia").warn("music controller shutdown failed", e);
        }
        if (!BotShutdown.stopAll(List.of(current.getDiscordAPI()))) {
            LoggerFactory.getLogger("Luffia").warn("discord api did not stop within the shutdown timeout");
        }
    }

    private static void startHaltWatchdog() {
        Thread watchdog = new Thread(() -> {
            try {
                Thread.sleep(SHUTDOWN_HALT_AFTER_MS);
            } catch (InterruptedException e) {
                return;
            }
            System.err.println("shutdown hook did not finish in time, halting jvm");
            Runtime.getRuntime().halt(0);
        }, "luffia-shutdown-watchdog");
        watchdog.setDaemon(true);
        watchdog.start();
    }

    private static void initScanner() {
        /* scanner start */
        while (true) {
            Scanner scanner = new Scanner(System.in);
            String input = scanner.nextLine();
            if (input.equals("stop")) {
                if (!isInitialized()) {
                    System.out.println("luffia isn't initialized.");
                    return;
                }
                System.exit(0);
            }
            System.out.println(input);
            /* scanner end */
        }
    }

    public static boolean isInitialized() {
        return luffia != null;
    }
}