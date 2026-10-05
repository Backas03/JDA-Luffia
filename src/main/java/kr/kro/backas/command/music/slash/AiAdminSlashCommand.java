package kr.kro.backas.command.music.slash;

import kr.kro.backas.BuildInfo;
import kr.kro.backas.Main;
import kr.kro.backas.command.api.SlashCommandSource;
import kr.kro.backas.music.MusicEmbeds;
import kr.kro.backas.music.MusicPlayerController;
import kr.kro.backas.music.ai.AiGuard;
import kr.kro.backas.music.llm.LlmScheduler;
import kr.kro.backas.music.lyrics.TranslationClient;
import kr.kro.backas.util.OwnerUtil;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.interactions.commands.OptionMapping;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.interactions.commands.build.OptionData;
import net.dv8tion.jda.api.interactions.commands.build.SlashCommandData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class AiAdminSlashCommand implements SlashCommandSource {
    private static final Logger LOGGER = LoggerFactory.getLogger(AiAdminSlashCommand.class);
    public static final String COMMAND_NAME = "ai관리";
    public static final String ACTION_ARGUMENT = "동작";
    private static final String ENABLE = "켜기";
    private static final String DISABLE = "끄기";
    private static final String STATUS = "상태";
    private static final String DEBUG_ENABLE = "디버그 켜기";
    private static final String DEBUG_DISABLE = "디버그 끄기";

    @Override
    public SlashCommandData buildCommand() {
        return Commands.slash(COMMAND_NAME, getDescription())
                .addOptions(new OptionData(OptionType.STRING, ACTION_ARGUMENT, "켜기, 끄기, 상태, 디버그 켜기, 디버그 끄기", true)
                        .addChoice(ENABLE, ENABLE)
                        .addChoice(DISABLE, DISABLE)
                        .addChoice(STATUS, STATUS)
                        .addChoice(DEBUG_ENABLE, DEBUG_ENABLE)
                        .addChoice(DEBUG_DISABLE, DEBUG_DISABLE));
    }

    @Override
    public void onTriggered(SlashCommandInteractionEvent event) {
        if (!OwnerUtil.isOwner(event.getUser())) {
            event.replyEmbeds(MusicEmbeds.error(event.getMember(),
                    "사용할 수 없는 명령어입니다.",
                    "봇 제작자만 AI 기능을 켜고 끌 수 있습니다.").build()).setEphemeral(true).queue();
            return;
        }
        MusicPlayerController controller = Main.getLuffia().getMusicPlayerController();
        AiGuard guard = controller.getAiGuard();
        OptionMapping option = event.getOption(ACTION_ARGUMENT);
        String action = option == null ? STATUS : option.getAsString();
        switch (action) {
            case ENABLE -> guard.setEnabled(true);
            case DISABLE -> guard.setEnabled(false);
            case DEBUG_ENABLE -> guard.setDebug(true);
            case DEBUG_DISABLE -> guard.setDebug(false);
            default -> {
            }
        }
        if (!STATUS.equals(action)) {
            LOGGER.info("ai {}, debug display {} by {}", guard.isEnabled() ? "enabled" : "disabled",
                    guard.isDebug() ? "on" : "off", event.getUser().getId());
        }
        TranslationClient translator = controller.getTranslationClient();
        EmbedBuilder builder = new EmbedBuilder()
                .setColor(guard.isEnabled() ? MusicEmbeds.SUCCESS : MusicEmbeds.ERROR)
                .setTitle("AI 기능: " + (guard.isEnabled() ? "켜짐" : "꺼짐"))
                .addField("사용 제한", "사용자별 " + AiGuard.USER_COOLDOWN_MS / 1000 + "초, 서버별 시간당 "
                        + AiGuard.GUILD_HOURLY_LIMIT + "회, 대기열 " + AiGuard.MAX_QUEUE + "곡, AI 셔플 GPU당 "
                        + AiGuard.SHUFFLE_TRACKS_PER_GPU + "곡 (지금 최대 "
                        + AiGuard.maxShuffleTracks(translator.liveGpus().size()) + "곡)", false)
                .addField("지금", translator.computeSummary(), false);
        for (LlmScheduler.EndpointStatus status : translator.scheduler().snapshot()) {
            builder.addField(status.label() + (status.fallback() ? " (CPU 예비)" : ""), describe(status), false);
        }
        builder.addField("디버그 표시", guard.isDebug() ? "켜짐 (가사 하단에 GPU, token/s, 진행률 표시)" : "꺼짐", false);
        event.replyEmbeds(builder.setFooter(BuildInfo.VERSION).build()).setEphemeral(true).queue();
    }

    private static String describe(LlmScheduler.EndpointStatus status) {
        StringBuilder text = new StringBuilder()
                .append(status.available() ? "정상" : "연결 안 됨")
                .append(" | 모델 ").append(status.modelName().isBlank() ? "-" : status.modelName())
                .append(" | 사용 중 ").append(status.inUse()).append("/").append(status.slots())
                .append(" | 현재 ").append(Math.round(status.currentTokensPerSecond())).append(" token/s\n동시 세션별 속도: ");
        for (int i = 0; i < status.speeds().length; i++) {
            if (i > 0) text.append(" · ");
            text.append(i + 1).append("개 ").append(Math.round(status.speeds()[i])).append(" token/s");
            if (!status.measured()[i]) text.append("(추정)");
        }
        return text.toString();
    }

    @Override
    public String getDescription() {
        return "AI 기능을 켜거나 끄고 상태와 디버그 표시를 관리합니다 (봇 제작자 전용)";
    }

    @Override
    public String getUsage() {
        return "/" + COMMAND_NAME + " <" + ACTION_ARGUMENT + ">";
    }
}
