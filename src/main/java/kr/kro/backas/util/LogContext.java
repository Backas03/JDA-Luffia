package kr.kro.backas.util;

import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.User;
import org.jetbrains.annotations.Nullable;
import org.slf4j.MDC;

public final class LogContext {

    public static final String KEY = "ctx";

    private LogContext() {
    }

    public static MDC.MDCCloseable enter(@Nullable Guild guild, @Nullable User user, String command) {
        StringBuilder value = new StringBuilder();
        value.append(guild == null ? "dm" : "g=" + guild.getId());
        if (user != null) value.append(" u=").append(user.getId());
        if (command != null && !command.isBlank()) value.append(' ').append(command);
        return MDC.putCloseable(KEY, value.toString());
    }
}
