package kr.kro.backas.util;

import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.joran.JoranConfigurator;
import ch.qos.logback.core.joran.spi.JoranException;
import ch.qos.logback.core.util.StatusPrinter;
import kr.kro.backas.config.LuffiaConfig;
import org.slf4j.ILoggerFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;

public final class LogSetup {

    public static final String RESOURCE = "/logback-luffia.xml";
    public static final String DIR_PROPERTY = "luffia.log.dir";
    public static final String LEVEL_PROPERTY = "luffia.log.level";

    private LogSetup() {
    }

    public static void apply(LuffiaConfig.Logging logging) {
        ILoggerFactory factory = LoggerFactory.getILoggerFactory();
        if (!(factory instanceof LoggerContext context)) return;
        context.reset();
        context.putProperty(DIR_PROPERTY, logging.dir());
        context.putProperty(LEVEL_PROPERTY, logging.level().toUpperCase());
        try (InputStream in = LogSetup.class.getResourceAsStream(RESOURCE)) {
            if (in == null) throw new IllegalStateException("로그 설정 리소스가 없습니다: " + RESOURCE);
            JoranConfigurator configurator = new JoranConfigurator();
            configurator.setContext(context);
            configurator.doConfigure(in);
        } catch (JoranException | IOException e) {
            throw new IllegalStateException("로그 설정을 적용하지 못했습니다", e);
        }
        if (!logging.console()) {
            context.getLogger(Logger.ROOT_LOGGER_NAME).detachAppender("CONSOLE");
        }
        StatusPrinter.printInCaseOfErrorsOrWarnings(context);
        LoggerFactory.getLogger(LogSetup.class).info("로그 파일을 {} 에 기록합니다 (레벨 {})",
                Path.of(logging.dir()).toAbsolutePath(), logging.level().toUpperCase());
    }

    public static void shutdown() {
        if (LoggerFactory.getILoggerFactory() instanceof LoggerContext context) {
            context.stop();
        }
    }
}
