package kr.kro.backas.util;

import ch.qos.logback.classic.LoggerContext;
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
    void restore() {
        LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
        context.stop();
        context.reset();
        context.start();
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
}
