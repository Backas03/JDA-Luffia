package kr.kro.backas;

import kr.kro.backas.certification.CertificationManager;
import kr.kro.backas.command.*;
import kr.kro.backas.command.api.CommandManager;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Activity;

import java.io.IOException;

public class Luffia {

    private final JDA discordAPI;
    private final CommandManager commandManager;
    private final CertificationManager certificationManager;

    public Luffia(JDA discordAPI) throws IOException {
        this.discordAPI = discordAPI;

        this.commandManager = new CommandManager("!", discordAPI);
        this.commandManager.registerCommand("인증", new CertificationCommand());
        this.commandManager.registerCommand("정보", new CertificationInfoCommand());
        this.commandManager.registerCommand("인증해제", new CertificationRemoveCommand());
        this.commandManager.registerCommand("도움말", new HelpCommand());
        this.commandManager.registerCommand("강제인증", new ForceCertificationCommand());

        this.certificationManager = new CertificationManager(discordAPI);

        this.discordAPI.getPresence().setActivity(Activity.playing("!도움말 명령어로 기능 확인"));
    }

    public JDA getDiscordAPI() {
        return discordAPI;
    }

    public CommandManager getCommandManager() {
        return commandManager;
    }

    public CertificationManager getCertificationManager() {
        return certificationManager;
    }
}
