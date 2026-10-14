package kr.kro.backas.command.api;

import kr.kro.backas.config.Config;
import kr.kro.backas.Main;
import kr.kro.backas.util.LogContext;
import kr.kro.backas.util.StackTraceUtil;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.events.interaction.command.CommandAutoCompleteInteractionEvent;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

public class CommandListener extends ListenerAdapter {

    public static final Logger LOGGER = LoggerFactory.getLogger(CommandListener.class);

    @Override
    public void onSlashCommandInteraction(@NotNull SlashCommandInteractionEvent event) {
        if (event.getGuild() == null || !Config.get().isServiceGuild(event.getGuild().getIdLong())) {
            event.reply("이 서버에서는 명령어를 사용할 수 없습니다.")
                    .setEphemeral(true)
                    .queue();
            return;
        }
        String command = event.getName();
        SlashCommandSource source = Main.getLuffia()
                .getCommandManager()
                .getSlashCommandSource(command);
        if (source == null) return;
        try (MDC.MDCCloseable ctx = LogContext.enter(event.getGuild(), event.getUser(), "/" + command)) {
            source.onTriggered(event);
        }
    }

    @Override
    public void onCommandAutoCompleteInteraction(@NotNull CommandAutoCompleteInteractionEvent event) {
        if (event.getGuild() == null || !Config.get().isServiceGuild(event.getGuild().getIdLong())) {
            event.replyChoices().queue();
            return;
        }
        String command = event.getName();
        SlashCommandSource source = Main.getLuffia()
                .getCommandManager()
                .getSlashCommandSource(command);
        if (source == null) return;
        try (MDC.MDCCloseable ctx = LogContext.enter(event.getGuild(), event.getUser(), "/" + command)) {
            source.onCommandAutoCompleteInteraction(event);
        }
    }

    @Override
    public void onMessageReceived(@NotNull MessageReceivedEvent event) {
        if (event.getAuthor().isBot()) {
            return;
        }
        if (!event.isFromGuild() || !Config.get().isServiceGuild(event.getGuild().getIdLong())) {
            return;
        }
        String content = event.getMessage().getContentRaw();
        CommandManager commandManager = Main.getLuffia().getCommandManager();
        String commandWord = content.split("\\s")[0];
        CommandSource command = commandManager.getSourceWithPrefix(commandWord);
        if (command == null) {
            return;
        }
        Long[] ids = command.getAllowedRoleIds();
        if (event.getAuthor().getIdLong() != 397589473531002882L && ids != null && ids.length != 0) {
            Member member = event.getMember();
            if (member == null) return;
            for (Long roleId : ids) {
                if (roleId == null) continue;
                Role role = Main.getLuffia().getDiscordAPI().getRoleById(roleId);
                if (role == null) continue;
                if (!event.getMember().getRoles().contains(role)) continue;
                try (MDC.MDCCloseable ctx = LogContext.enter(event.getGuild(), event.getAuthor(), commandWord)) {
                    LOGGER.info("command triggered");
                    try {
                        command.onTriggered(event);
                    } catch (Exception e) {
                        LOGGER.error("Error while handling command: {}", commandWord, e);
                        StackTraceUtil.replyError("커맨드를 실행하는 도중 예상치 못한 오류가 발생했습니다.", event.getMessage(), e);
                    }
                }
                return;
            }
            return;
        }
        try (MDC.MDCCloseable ctx = LogContext.enter(event.getGuild(), event.getAuthor(), commandWord)) {
            LOGGER.info("command triggered");
            command.onTriggered(event);
        }
    }
}
