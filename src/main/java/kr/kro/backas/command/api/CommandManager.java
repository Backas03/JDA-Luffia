package kr.kro.backas.command.api;

import kr.kro.backas.Luffia;
import net.dv8tion.jda.api.JDA;

import java.util.HashMap;
import java.util.Map;

public class CommandManager {

    private final Map<String, CommandSource> REGISTERED = new HashMap<>();
    private final Map<String, SlashCommandSource> REGISTERED_SLASH_COMMAND = new HashMap<>();

    public String commandPrefix;
    private final Luffia luffia;

    public CommandManager(String commandPrefix, Luffia luffia) {
        this.commandPrefix = commandPrefix;
        this.luffia = luffia;
        luffia.getPublishedGuild().updateCommands().queue();
        this.luffia.getDiscordAPI().addEventListener(new CommandListener());
    }

    public void registerCommand(String command, CommandSource source) {
        REGISTERED.put(command, source);
    }

    public void registerSlashCommand(SlashCommandSource source) {
        luffia.getPublishedGuild()
                .upsertCommand(source.buildCommand())
                .queue(command -> {
                    // on success
                    REGISTERED_SLASH_COMMAND.put(command.getName(), source);
                });

    }

    public SlashCommandSource getSlashCommandSource(String command) {
        return REGISTERED_SLASH_COMMAND.get(command);
    }

    public CommandSource getSource(String command) {
        return REGISTERED.get(command);
    }

    public CommandSource getSourceWithPrefix(String command) {
        if (!command.startsWith(commandPrefix)) return null;
        return REGISTERED.get(command.replaceFirst(commandPrefix, ""));
    }

    public Map<String, CommandSource> getSources() {
        return REGISTERED;
    }

    public boolean hasSource(String command) {
        return REGISTERED.containsKey(command);
    }
}
