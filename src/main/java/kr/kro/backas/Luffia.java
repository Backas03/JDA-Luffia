package kr.kro.backas;

import kr.kro.backas.certification.CertificationManager;
import kr.kro.backas.certification.listener.CertificationListener;
import kr.kro.backas.command.*;
import kr.kro.backas.command.api.CommandManager;
import kr.kro.backas.command.certification.CertificationCommand;
import kr.kro.backas.command.certification.CertificationInfoCommand;
import kr.kro.backas.command.certification.CertificationRemoveCommand;
import kr.kro.backas.command.certification.ForceCertificationCommand;
import kr.kro.backas.command.lol.LOLUserInfoCommand;
import kr.kro.backas.command.maplestory.MapleUserInfoCommand;
import kr.kro.backas.command.music.*;
import kr.kro.backas.music.MusicListener;
import kr.kro.backas.music.MusicPlayerController;
import kr.kro.backas.secret.BotSecret;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Activity;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.managers.AudioManager;

import java.io.IOException;

public class Luffia {

    private final JDA discordAPI;
    private final CommandManager commandManager;
    private final CertificationManager certificationManager;
    private final MusicPlayerController musicPlayerController;

    public Luffia(JDA discordAPI) throws IOException, InterruptedException {
        this.discordAPI = discordAPI;

        this.commandManager = new CommandManager("!", discordAPI);
        this.commandManager.registerCommand("인증", new CertificationCommand());
        this.commandManager.registerCommand("인증정보", new CertificationInfoCommand());
        this.commandManager.registerCommand("인증해제", new CertificationRemoveCommand());
        this.commandManager.registerCommand("도움말", new HelpCommand());
        this.commandManager.registerCommand("강제인증", new ForceCertificationCommand());

        this.commandManager.registerCommand("재생", new PlayCommand());
        this.commandManager.registerCommand("나가기", new QuitCommand());
        this.commandManager.registerCommand("스킵", new SkipCommand());
        this.commandManager.registerCommand("일시정지", new PauseCommand());
        this.commandManager.registerCommand("일시정지해제", new ResumeCommand());
        //this.commandManager.registerCommand("전체반복", new RepeatAllCommand());
        //this.commandManager.registerCommand("반복", new RepeatCurrentCommand());
        //this.commandManager.registerCommand("반복해제", new NoRepeatCommand());
        this.commandManager.registerCommand("대기열", new QueueCommand());

        this.commandManager.registerCommand("롤정보", new LOLUserInfoCommand());

        this.commandManager.registerCommand("메이플정보", new MapleUserInfoCommand());

        this.certificationManager = new CertificationManager(discordAPI);

        this.musicPlayerController = new MusicPlayerController();
        discordAPI.addEventListener(this.musicPlayerController);
        for (String botToken : BotSecret.MUSIC_BOT_TOKENS) {
            this.musicPlayerController.register(botToken);
        }


        this.discordAPI.addEventListener(new MusicListener());
        this.discordAPI.addEventListener(new CertificationListener());
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

    public Guild getPublishedGuild() {
        return SharedConstant.ON_DEV ?
                discordAPI.getGuildById(1121632283154202694L) :
                discordAPI.getGuildById(SharedConstant.PUBLISHED_GUILD_ID);
    }

    public MusicPlayerController getMusicPlayerController() {
        return musicPlayerController;
    }
}
