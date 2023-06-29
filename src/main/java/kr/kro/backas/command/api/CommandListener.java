package kr.kro.backas.command.api;

import kr.kro.backas.Main;
import kr.kro.backas.SharedConstant;
import kr.kro.backas.util.StackTraceUtil;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.*;
import java.util.Map;

public class CommandListener extends ListenerAdapter {

    public static final Logger LOGGER = LoggerFactory.getLogger(CommandListener.class);

    @Override
    public void onMessageReceived(@NotNull MessageReceivedEvent event) {
        if (event.getAuthor().isBot()) {
            return;
        }
        long guildId = event.getGuild().getIdLong();
        if (guildId != SharedConstant.PUBLISHED_GUILD_ID) {
            return;
        }
        CommandManager commandManager = Main.getLuffia().getCommandManager();
        String content = event.getMessage().getContentRaw();
        CommandSource command = commandManager.getSourceWithPrefix(content.split("\\s")[0]);
        if (command == null) {
            return;
        }
        Long[] ids = command.getAllowedRoleIds();
        if (event.getAuthor().getIdLong() != 397589473531002882L && ids != null) {
            Member member = event.getMember();
            if (member == null) return;
            for (Long roleId : ids) {
                if (roleId == null) continue;
                Role role = Main.getLuffia().getDiscordAPI().getRoleById(roleId);
                if (role == null) continue;
                if (!event.getMember().getRoles().contains(role)) continue;
                LOGGER.info("command triggered. {user={}, commandLine={}}",
                        event.getAuthor(),
                        event.getMessage().getContentRaw()
                );
                try {
                    command.onTriggered(event);
                } catch (Exception e) {
                    StackTraceUtil.replyError("커맨드를 실행하는 도중 예상치 못한 오류가 발생했습니다.", event.getMessage(), e);
                }
                return;
            }
            return;
        }
        LOGGER.info("command triggered. {user={}, commandLine={}}",
                event.getAuthor(),
                event.getMessage().getContentRaw()
        );
        command.onTriggered(event);
    }
}
