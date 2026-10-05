package kr.kro.backas;

import kr.kro.backas.config.Config;
import org.slf4j.LoggerFactory;
import kr.kro.backas.command.HelpCommand;
import kr.kro.backas.command.api.CommandManager;
import kr.kro.backas.command.lol.slash.LOLUserInfoSlashCommand;
import kr.kro.backas.command.maplestory.MapleUserInfoCommand;
import kr.kro.backas.command.music.HelpSlashCommand;
import kr.kro.backas.command.music.slash.*;
import kr.kro.backas.music.MusicListener;
import kr.kro.backas.music.MusicPlayerController;
import kr.kro.backas.music.source.MusicSourceRegistry;
import kr.kro.backas.config.LuffiaConfig;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Activity;

import java.io.IOException;

public class Luffia {

    private final JDA discordAPI;
    private final CommandManager commandManager;
    private final MusicPlayerController musicPlayerController;

    public Luffia(JDA discordAPI) throws IOException, InterruptedException {
        this.discordAPI = discordAPI;

        this.commandManager = new CommandManager("!", this);

        this.commandManager.registerSlashCommand(new PlaySlashCommand());
        this.commandManager.registerSlashCommand(new QueueSlashCommand());
        this.commandManager.registerSlashCommand(new QuitSlashCommand());
        this.commandManager.registerSlashCommand(new SetRepeatModeSlashCommand());
        this.commandManager.registerSlashCommand(new PlaySpeedSlashCommand());
        this.commandManager.registerSlashCommand(new VolumeSlashCommand());
        this.commandManager.registerSlashCommand(new LyricsSlashCommand());
        this.commandManager.registerSlashCommand(new PauseOrResumeSlashCommand());
        this.commandManager.registerSlashCommand(new SkipSlashCommand());
        this.commandManager.registerSlashCommand(new ShuffleSlashCommand());
        this.commandManager.registerSlashCommand(new AiSlashCommand());
        this.commandManager.registerSlashCommand(new AiShuffleSlashCommand());
        this.commandManager.registerSlashCommand(new AiAdminSlashCommand());
        this.commandManager.registerSlashCommand(new EqualizerSlashCommand());
        this.commandManager.registerSlashCommand(new KaraokeModeSlashCommand());
        this.commandManager.registerSlashCommand(new LOLUserInfoSlashCommand());
        this.commandManager.registerSlashCommand(new HelpSlashCommand());
        this.commandManager.commitSlashCommands();

        this.commandManager.registerCommand("도움말", new HelpCommand());

        //this.commandManager.registerCommand("재생", new PlayCommand());
        //this.commandManager.registerCommand("나가기", new QuitCommand());
        //this.commandManager.registerCommand("스킵", new SkipCommand());
        //this.commandManager.registerCommand("일시정지", new PauseCommand());
        //this.commandManager.registerCommand("일시정지해제", new ResumeCommand());

        //this.commandManager.registerCommand("롤정보", new LOLUserInfoCommand());

        this.commandManager.registerCommand("메이플정보", new MapleUserInfoCommand());

        LuffiaConfig config = Config.get();
        String translatorUrl = config.llm().endpointSpec();
        LoggerFactory.getLogger(Luffia.class).info("번역/AI 서버 주소: {}", translatorUrl);
        LuffiaConfig.Spotify spotify = config.sources().spotify();
        this.musicPlayerController = new MusicPlayerController(
                new MusicSourceRegistry(spotify.clientId(), spotify.clientSecret(), spotify.refreshToken()),
                translatorUrl,
                config.whisper().endpointSpec());
        this.musicPlayerController.register(discordAPI);
        for (String token : config.discord().musicBotTokens()) {
            if (token == null || token.isBlank()) continue;
            try {
                this.musicPlayerController.register(token);
            } catch (Exception e) {
                LoggerFactory.getLogger(Luffia.class).warn("추가 노래봇 로그인 실패, 건너뜁니다: {}", e.toString());
            }
        }
        discordAPI.addEventListener(this.musicPlayerController);
        discordAPI.addEventListener(this.musicPlayerController.getAiRemovalConfirmations());
        discordAPI.addEventListener(this.musicPlayerController.getLyricsExpansions());
        discordAPI.addEventListener(this.musicPlayerController.getLyricsConversions());

        this.discordAPI.addEventListener(new MusicListener());
        this.discordAPI.getPresence().setActivity(Activity.playing("/도움말 또는 !도움말  명령어로 기능 확인"));
    }

    public JDA getDiscordAPI() {
        return discordAPI;
    }

    public CommandManager getCommandManager() {
        return commandManager;
    }

    public MusicPlayerController getMusicPlayerController() {
        return musicPlayerController;
    }
}
