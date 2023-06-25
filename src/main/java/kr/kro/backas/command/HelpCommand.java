package kr.kro.backas.command;

import kr.kro.backas.Main;
import kr.kro.backas.SharedConstant;
import kr.kro.backas.command.api.CommandManager;
import kr.kro.backas.command.api.CommandSource;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;

import java.awt.*;
import java.util.Map;

public class HelpCommand implements CommandSource {
    @Override
    public void onTriggered(MessageReceivedEvent event) {
        CommandManager commandManager = Main.getLuffia()
                .getCommandManager();
        Map<String, CommandSource> sources = commandManager.getSources();
        EmbedBuilder builder = new EmbedBuilder()
                .setColor(Color.decode("#75f971"))
                .setTitle("Luffia 도움말")
                .setDescription("""
                            Java Discord API(JDA) 로 여러 기능을 서비스하는 디스코드 봇 Luffia 입니다.
                            이 봇은 bagkaseu(박카스#9970) 에 의해 제작되었으며, 대구대 게임 전공 서버
                            이외에 서비스를 하지 않습니다.
                            해당 봇의 오류나 건의, 문의사항이 있을시 제작자에게 문의 바랍니다.
                            
                            * 현재 Luffia는 오전 0시 ~ 10시 사이동안 서비스를 하지 않습니다.
                            * 24시간 서비스시 해당 문구가 사라질 예정입니다.

                            아래는 해당 봇의 기능입니다.""");
        for (Map.Entry<String, CommandSource> entry : sources.entrySet()) {
            CommandSource source = entry.getValue();
            if (source instanceof HelpCommand) continue; // bypass
            String perm = "";
            Long[] roleIds = source.getAllowedRoleIds();
            if (roleIds != null) {
                StringBuilder sb = new StringBuilder();
                int i = 0;
                for (; i<roleIds.length; i++) {
                    Long roleId = roleIds[i];
                    if (roleId == null) continue;
                    Role role = event.getGuild().getRoleById(roleId);
                    if (role == null) continue;
                    sb = new StringBuilder(" " + role.getAsMention());
                    break;
                }
                for (++i; i<roleIds.length; i++) {
                    Long roleId = roleIds[i];
                    if (roleId == null) continue;
                    Role role = event.getGuild().getRoleById(roleId);
                    if (role == null) continue;
                    sb.append(" 또는 ").append(role.getAsMention());
                }
                perm = "(" + sb.append(" 역할이 필요합니다") + ")";
            }
            String value = source.getDescription() != null ? " - " + source.getDescription() : "";
            builder.addField(
                    commandManager.commandPrefix + entry.getKey() + value,
                    source.getUsage() + perm,
                    false
            );
        }
        builder.setFooter("Bot Version: " + SharedConstant.RELEASE_VERSION);
        event.getMessage().replyEmbeds(builder.build()).queue();
    }

    @Override
    public String getDescription() {
        return null;
    }

    @Override
    public String getUsage() {
        return null;
    }

    @Override
    public Long[] getAllowedRoleIds() {
        return null;
    }
}
