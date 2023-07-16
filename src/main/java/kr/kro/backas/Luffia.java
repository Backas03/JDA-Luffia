package kr.kro.backas;

import kr.kro.backas.certification.CertificationManager;
import kr.kro.backas.civilwar.lol.LOLUserInfo;
import kr.kro.backas.command.*;
import kr.kro.backas.command.api.CommandManager;
import kr.kro.backas.command.certification.CertificationCommand;
import kr.kro.backas.command.certification.CertificationInfoCommand;
import kr.kro.backas.command.certification.CertificationRemoveCommand;
import kr.kro.backas.command.certification.ForceCertificationCommand;
import kr.kro.backas.command.music.*;
import kr.kro.backas.music.MusicListener;
import kr.kro.backas.music.MusicPlayerManager;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Activity;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.channel.concrete.VoiceChannel;
import net.dv8tion.jda.api.managers.AudioManager;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;

public class Luffia {

    private final JDA discordAPI;
    private final CommandManager commandManager;
    private final CertificationManager certificationManager;
    private final MusicPlayerManager musicPlayerManager;

    public Luffia(JDA discordAPI) throws IOException {
        this.discordAPI = discordAPI;

        this.commandManager = new CommandManager("!", discordAPI);
        this.commandManager.registerCommand("인증", new CertificationCommand());
        this.commandManager.registerCommand("인증정보", new CertificationInfoCommand());
        this.commandManager.registerCommand("인증해제", new CertificationRemoveCommand());
        this.commandManager.registerCommand("도움말", new HelpCommand());
        this.commandManager.registerCommand("강제인증", new ForceCertificationCommand());

        this.commandManager.registerCommand("재생", new PlayCommand());
        this.commandManager.registerCommand("참여", new JoinCommand());
        this.commandManager.registerCommand("나가기", new QuitCommand());
        this.commandManager.registerCommand("스킵", new SkipCommand());
        this.commandManager.registerCommand("정보", new NowPlayingCommand());
        this.commandManager.registerCommand("정지", new PauseCommand());
        this.commandManager.registerCommand("전체반복", new RepeatAllCommand());
        this.commandManager.registerCommand("반복", new RepeatCurrentCommand());
        this.commandManager.registerCommand("반복해제", new NoRepeatCommand());
        this.commandManager.registerCommand("대기열", new QueueCommand());

        this.certificationManager = new CertificationManager(discordAPI);
        this.musicPlayerManager = new MusicPlayerManager(this);

        this.discordAPI.addEventListener(new MusicListener());
        this.discordAPI.getPresence().setActivity(Activity.playing("!도움말 명령어로 기능 확인"));
    }

    public MusicPlayerManager getMusicPlayerManager() {
        return musicPlayerManager;
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

    public void connectVoice(@NotNull VoiceChannel channel) {
        Guild guild = getPublishedGuild();
        AudioManager audioManager = guild.getAudioManager();
        disconnectVoice();
        audioManager.openAudioConnection(channel);
    }

    public void disconnectVoice() {
        Guild guild = getPublishedGuild();
        AudioManager audioManager = guild.getAudioManager();
        if (audioManager.isConnected()) {
            audioManager.closeAudioConnection();
        }
    }

    public boolean isVoiceConnected() {
        return getPublishedGuild().getAudioManager().isConnected();
    }
}
