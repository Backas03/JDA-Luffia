package kr.kro.backas.command.api;

import net.dv8tion.jda.api.JDA;

import java.util.HashMap;
import java.util.Map;

public class CommandManager {

    private final Map<String, CommandSource> REGISTERED = new HashMap<>();

    public String commandPrefix;

    public CommandManager(String commandPrefix, JDA discordAPI) {
        this.commandPrefix = commandPrefix;
        discordAPI.addEventListener(new CommandListener());
    }

    public void registerCommand(String command, CommandSource source) {
        REGISTERED.put(command, source);
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
