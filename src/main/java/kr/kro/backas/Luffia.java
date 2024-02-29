package kr.kro.backas;

import kr.kro.backas.certification.CertificationManager;
import kr.kro.backas.certification.listener.CertificationListener;
import kr.kro.backas.command.HelpCommand;
import kr.kro.backas.command.api.CommandManager;
import kr.kro.backas.command.lol.slash.LOLUserInfoSlashCommand;
import kr.kro.backas.command.maplestory.MapleUserInfoCommand;
import kr.kro.backas.command.music.HelpSlashCommand;
import kr.kro.backas.command.music.slash.*;
import kr.kro.backas.music.MusicListener;
import kr.kro.backas.music.MusicPlayerController;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Activity;
import net.dv8tion.jda.api.entities.Guild;

import java.io.IOException;

public class Luffia {

    private final JDA discordAPI;
    private final CommandManager commandManager;
    private final CertificationManager certificationManager;
    private final MusicPlayerController musicPlayerController;

    public Luffia(JDA discordAPI) throws IOException, InterruptedException {
        this.discordAPI = discordAPI;

        this.commandManager = new CommandManager("!", this);

        //this.commandManager.registerSlashCommand(new SlashCertificationCommand());
        this.commandManager.registerSlashCommand(new PlaySlashCommand());
        this.commandManager.registerSlashCommand(new QueueSlashCommand());
        this.commandManager.registerSlashCommand(new QuitSlashCommand());
        this.commandManager.registerSlashCommand(new SetRepeatModeSlashCommand());
        this.commandManager.registerSlashCommand(new PlaySpeedSlashCommand());
        this.commandManager.registerSlashCommand(new PauseOrResumeSlashCommand());
        this.commandManager.registerSlashCommand(new SkipSlashCommand());
        this.commandManager.registerSlashCommand(new EqualizerSlashCommand());
        this.commandManager.registerSlashCommand(new KaraokeModeSlashCommand());
        this.commandManager.registerSlashCommand(new LOLUserInfoSlashCommand());
        this.commandManager.registerSlashCommand(new HelpSlashCommand());

        //this.commandManager.registerCommand("인증정보", new CertificationInfoCommand());
        //this.commandManager.registerCommand("인증해제", new CertificationRemoveCommand());
        this.commandManager.registerCommand("도움말", new HelpCommand());
        //this.commandManager.registerCommand("강제인증", new ForceCertificationCommand());

        //this.commandManager.registerCommand("재생", new PlayCommand());
        //this.commandManager.registerCommand("나가기", new QuitCommand());
        //this.commandManager.registerCommand("스킵", new SkipCommand());
        //this.commandManager.registerCommand("일시정지", new PauseCommand());
        //this.commandManager.registerCommand("일시정지해제", new ResumeCommand());

        //this.commandManager.registerCommand("롤정보", new LOLUserInfoCommand());

        this.commandManager.registerCommand("메이플정보", new MapleUserInfoCommand());

        this.certificationManager = null; // new CertificationManager(discordAPI);

        this.musicPlayerController = new MusicPlayerController();
        this.musicPlayerController.register(discordAPI);
        discordAPI.addEventListener(this.musicPlayerController);

        this.discordAPI.addEventListener(new MusicListener());
        this.discordAPI.addEventListener(new CertificationListener());
        this.discordAPI.getPresence().setActivity(Activity.playing("/도움말 또는 !도움말  명령어로 기능 확인"));
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

    public Guild getPublishedGuild() {
        return SharedConstant.ON_DEV ?
                discordAPI.getGuildById(SharedConstant.DEV_GUILD_ID) :
                discordAPI.getGuildById(SharedConstant.PUBLISHED_GUILD_ID);
    }

    public MusicPlayerController getMusicPlayerController() {
        return musicPlayerController;
    }
}
