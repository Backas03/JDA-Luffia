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

    default String getArgument(String input) {
        String[] split = input.split(SPLIT);
        return getArgument(input, 0, split.length - 1);
    }

    default String getArgument(String input, int start, int end) {
        String[] split = input.split(SPLIT);
        if (split.length - 1 > start) {
            StringBuilder sb = new StringBuilder(split[start + 1]);
            for (int i=start+2; i<end; i++) {
                sb.append(" ").append(split[i]);
            }
            return sb.toString();
        }
        return null;
    }

    default String getArgument(String[] split, int end) {
        if (split.length > 1) {
            StringBuilder sb = new StringBuilder(split[1]);
            for (int i=2; i<end; i++) {
                sb.append(" ").append(split[i]);
            }
            return sb.toString();
        }
        return null;
    }

    default String getArgument(String[] split) {
        if (split.length > 1) {
            StringBuilder sb = new StringBuilder(split[1]);
            for (int i=2; i<split.length; i++) {
                sb.append(" ").append(split[i]);
            }
            return sb.toString();
        }
        return null;
    }

    String SPLIT = "\\s+";
}
