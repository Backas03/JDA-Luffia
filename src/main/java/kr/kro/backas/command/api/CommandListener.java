package kr.kro.backas.command.api;

import com.sedmelluq.discord.lavaplayer.track.AudioPlaylist;
import kr.kro.backas.Main;
import kr.kro.backas.SharedConstant;
import kr.kro.backas.music.MusicPlayerManager;
import kr.kro.backas.util.StackTraceUtil;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class CommandListener extends ListenerAdapter {

    public static final Logger LOGGER = LoggerFactory.getLogger(CommandListener.class);

    @Override
    public void onMessageReceived(@NotNull MessageReceivedEvent event) {
        if (event.getAuthor().isBot()) {
            return;
        }
        long guildId = event.getGuild().getIdLong();
        long publishedGuildId = SharedConstant.ON_DEV ?
                SharedConstant.DEV_GUILD_ID :
                SharedConstant.PUBLISHED_GUILD_ID;
        if (guildId != publishedGuildId) {
            return;
        }
        String content = event.getMessage().getContentRaw();
        /* music select command start */
        try {
            int number = Integer.parseInt(content);
            Member member = event.getMember();
            if (member != null) {
                MusicPlayerManager manager = Main.getLuffia().getMusicPlayerManager();
                AudioPlaylist playlist = manager.getQuery(member.getIdLong());
                if (playlist != null) {
                    int max = Math.min(5, playlist.getTracks().size());
                    if (number > max) event.getMessage().reply("1 ~ " + max + "사이의 트랙 번호를 입력해주세요").queue();
                    else {
                        manager.enqueue(event.getMessage(), playlist.getTracks().get(number - 1));
                        manager.getQueries().remove(member.getIdLong());
                    }
                }
            }
        } catch (Exception e) {
            if (!(e instanceof NumberFormatException)) {
                StackTraceUtil.replyError("커맨드를 실행하는 도중 예상치 못한 오류가 발생했습니다.", event.getMessage(), e);
            }
        }
        /* music select command end */
        CommandManager commandManager = Main.getLuffia().getCommandManager();
        CommandSource command = commandManager.getSourceWithPrefix(content.split("\\s")[0]);
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
