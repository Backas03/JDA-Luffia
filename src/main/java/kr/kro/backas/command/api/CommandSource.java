package kr.kro.backas.command.api;


import net.dv8tion.jda.api.events.message.MessageReceivedEvent;

public interface CommandSource {

    void onTriggered(MessageReceivedEvent event);

    String getDescription();

    String getUsage();

    Long[] getAllowedRoleIds();

    default String getArgument(String input, int index) {
        String[] split = input.split("\\s+");
        if (split.length - 1 > index) {
            return split[index + 1];
        }
        return null;
    }
}
