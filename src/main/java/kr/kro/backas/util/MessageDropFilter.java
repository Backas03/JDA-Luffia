package kr.kro.backas.util;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.turbo.TurboFilter;
import ch.qos.logback.core.spi.FilterReply;
import org.slf4j.Marker;

public class MessageDropFilter extends TurboFilter {

    private String logger;
    private String message;

    public void setLogger(String logger) {
        this.logger = logger;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    @Override
    public FilterReply decide(Marker marker, Logger target, Level level, String format, Object[] params, Throwable t) {
        if (format == null || target == null || message == null || !message.equals(format)) return FilterReply.NEUTRAL;
        return logger == null || logger.equals(target.getName()) ? FilterReply.DENY : FilterReply.NEUTRAL;
    }
}
