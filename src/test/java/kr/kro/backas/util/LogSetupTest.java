package kr.kro.backas.util;

import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.util.ContextInitializer;
import ch.qos.logback.core.joran.spi.JoranException;
import kr.kro.backas.config.LuffiaConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LogSetupTest {

    @TempDir
    Path dir;

    @AfterEach
    void restore() throws JoranException {
        LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
        context.stop();
        context.reset();
        context.start();
        new ContextInitializer(context).autoConfig();
    }

    @Test
    void writesFileLogsWithContext() throws IOException {
        LogSetup.apply(new LuffiaConfig.Logging(dir.toString(), "debug", false));
        var logger = LoggerFactory.getLogger("kr.kro.backas.test");
        try (MDC.MDCCloseable ignored = MDC.putCloseable(LogContext.KEY, "g=1 u=2 /play")) {
            logger.debug("with context");
        }
        logger.info("without context");
        logger.warn("warned");
        LogSetup.shutdown();

        String all = Files.readString(dir.resolve("luffia.log"));
        String errors = Files.readString(dir.resolve("luffia-error.log"));
        assertTrue(all.contains("-- [g=1 u=2 /play] with context"), "all=[" + all + "] errors=[" + errors + "]");
        assertTrue(all.contains("-- without context"), all);
        assertFalse(all.contains("[] without context"), all);
        assertTrue(errors.contains("warned"), errors);
        assertFalse(errors.contains("without context"), errors);
    }

    @Test
    void dropsTheYoutubeUserDataTokenNoiseButKeepsClientFailures() throws IOException {
        LogSetup.apply(new LuffiaConfig.Logging(dir.toString(), "info", false));
        var youtube = LoggerFactory.getLogger("dev.lavalink.youtube.track.YoutubeAudioTrack");
        youtube.debug("Failed to parse token from userData", new IllegalArgumentException("Unrecognized token 'kr'"));
        youtube.debug("Client \"{}\" failed during playback storing and proceeding", "WEB");
        LoggerFactory.getLogger("kr.kro.backas.test").info("Failed to parse token from userData");
        LogSetup.shutdown();

        String all = Files.readString(dir.resolve("luffia.log"));
        assertFalse(all.contains("Unrecognized token"), all);
        assertTrue(all.contains("Client \"WEB\" failed during playback"), all);
        assertTrue(all.contains("kr.kro.backas.test -- Failed to parse token from userData"), all);
    }
}
