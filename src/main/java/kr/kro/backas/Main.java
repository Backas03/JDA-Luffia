package kr.kro.backas;

import kr.kro.backas.config.Config;
import club.minnced.discord.jdave.interop.JDaveSessionFactory;
import com.merakianalytics.orianna.Orianna;
import com.merakianalytics.orianna.types.common.Platform;
import kr.kro.backas.config.LuffiaConfig;
import java.nio.file.Path;
import kr.kro.backas.util.BotShutdown;
import kr.kro.backas.util.LogSetup;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.audio.AudioModuleConfig;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.requests.GatewayIntent;
import net.dv8tion.jda.api.utils.ChunkingFilter;
import net.dv8tion.jda.api.utils.MemberCachePolicy;
import net.dv8tion.jda.api.utils.cache.CacheFlag;
import net.dv8tion.jda.api.utils.messages.MessageRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.EnumSet;
import java.util.List;

public class Main {
    private static final Logger LOGGER = LoggerFactory.getLogger(Main.class);
    private static Luffia luffia;

    public static Luffia getLuffia() {
        return luffia;
    }

    public static void main(String[] args) {
        Path configPath = Config.externalPath();
        boolean created = Config.writeDefaultsIfMissing(configPath);
        LuffiaConfig config = Config.load(configPath);
        LogSetup.apply(config.logging());
        if (created) {
            LOGGER.info("기본 설정 파일을 만들었습니다: {} (토큰과 서버 주소를 채운 뒤 다시 실행하세요)", configPath.toAbsolutePath());
        }
        List<String> missing = Config.missingRequired(config);
        if (!missing.isEmpty()) {
            LOGGER.error("설정이 비어 있어 시작할 수 없습니다: {} ({})", String.join(", ", missing), configPath.toAbsolutePath());
            LogSetup.shutdown();
            System.exit(2);
            return;
        }
        Thread.setDefaultUncaughtExceptionHandler((thread, error) ->
                LOGGER.error("처리되지 않은 예외 (스레드 {})", thread.getName(), error));
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
            LOGGER.info("luffia 시작 완료 (서버 {}개)", jda.getGuilds().size());
        } catch (InterruptedException | IOException e) {
            LOGGER.error("디스코드 연결에 실패해 종료합니다", e);
            System.exit(-1);
        }
    }

    private static void stop() {
        startHaltWatchdog();
        LOGGER.info("stopping luffia...");
        try {
            Luffia current = luffia;
            if (current == null) return;
            try {
                current.getMusicPlayerController().shutdownGracefully();
            } catch (RuntimeException e) {
                LOGGER.warn("music controller shutdown failed", e);
            }
            if (!BotShutdown.stopAll(List.of(current.getDiscordAPI()))) {
                LOGGER.warn("discord api did not stop within the shutdown timeout");
            }
        } finally {
            LOGGER.info("luffia stopped");
            LogSetup.shutdown();
        }
    }

    private static void startHaltWatchdog() {
        Thread watchdog = new Thread(() -> {
            try {
                Thread.sleep(Config.get().bot().shutdown().haltAfterSeconds() * 1000L);
            } catch (InterruptedException e) {
                return;
            }
            LOGGER.error("shutdown hook did not finish in time, halting jvm");
            LogSetup.shutdown();
            Runtime.getRuntime().halt(0);
        }, "luffia-shutdown-watchdog");
        watchdog.setDaemon(true);
        watchdog.start();
    }

    public static boolean isInitialized() {
        return luffia != null;
    }
}