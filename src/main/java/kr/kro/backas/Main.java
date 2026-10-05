package kr.kro.backas;

import kr.kro.backas.config.Config;
import club.minnced.discord.jdave.interop.JDaveSessionFactory;
import com.merakianalytics.orianna.Orianna;
import com.merakianalytics.orianna.types.common.Platform;
import kr.kro.backas.config.LuffiaConfig;
import java.nio.file.Path;
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

    public static void main(String[] args) {
        Path configPath = Config.externalPath();
        if (Config.writeDefaultsIfMissing(configPath)) {
            System.out.println("기본 설정 파일을 만들었습니다: " + configPath.toAbsolutePath() + " (토큰과 서버 주소를 채운 뒤 다시 실행하세요)");
        }
        LuffiaConfig config = Config.load(configPath);
        List<String> missing = Config.missingRequired(config);
        if (!missing.isEmpty()) {
            System.err.println("설정이 비어 있어 시작할 수 없습니다: " + String.join(", ", missing) + " (" + configPath.toAbsolutePath() + ")");
            System.exit(2);
            return;
        }
        Runtime.getRuntime().addShutdownHook(new Thread(Main::stop, "luffia-shutdown"));
        MessageRequest.setDefaultMentions(EnumSet.noneOf(Message.MentionType.class));
        MessageRequest.setDefaultMentionRepliedUser(false);
        JDABuilder builder = JDABuilder
                .createDefault(config.discord().activeToken(config.bot().dev()))
                .setChunkingFilter(ChunkingFilter.ALL) // enable member chunking for all guilds
                .setMemberCachePolicy(MemberCachePolicy.ALL)
                .enableIntents(GatewayIntent.MESSAGE_CONTENT, GatewayIntent.GUILD_MEMBERS)
                .enableCache(CacheFlag.ROLE_TAGS)
                .setAudioModuleConfig(new AudioModuleConfig().withDaveSessionFactory(new JDaveSessionFactory()));

        try {
            Orianna.setRiotAPIKey(config.riot().apiKey());
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
                Thread.sleep(Config.get().bot().shutdown().haltAfterSeconds() * 1000L);
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