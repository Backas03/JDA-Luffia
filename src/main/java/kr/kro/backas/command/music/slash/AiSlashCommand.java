package kr.kro.backas.command.music.slash;

import kr.kro.backas.Main;
import kr.kro.backas.command.api.SlashCommandSource;
import kr.kro.backas.music.MusicPlayerClient;
import kr.kro.backas.music.MusicPlayerController;
import kr.kro.backas.music.ai.AiAgent;
import kr.kro.backas.music.lyrics.TranslationJobs;
import kr.kro.backas.util.DiscordSafe;
import kr.kro.backas.util.MemberUtil;
import kr.kro.backas.util.OwnerUtil;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.container.Container;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.channel.concrete.VoiceChannel;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.interactions.InteractionHook;
import net.dv8tion.jda.api.interactions.commands.OptionMapping;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.interactions.commands.build.OptionData;
import net.dv8tion.jda.api.interactions.commands.build.SlashCommandData;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class AiSlashCommand implements SlashCommandSource {
    private static final Logger LOGGER = LoggerFactory.getLogger(AiSlashCommand.class);
    public static final String COMMAND_NAME = "ai";
    public static final String REQUEST_ARGUMENT = "요청";
    private static final int MAX_REASON_DEPTH = 4;
    private static final int MAX_REASON_LENGTH = 1500;
    private static final String STATUS_TITLE = "AI 가 요청을 처리하고 있습니다";
    private static final String EXAMPLES = """
            이렇게 말해보세요.
            - 요즘 유행하는 jpop 틀어줘
            - 보카로곡 20곡 선정해서 틀어봐
            - 대기열에서 한국 노래 다 빼줘
            - 일본 노래만 남기고 섞어줘
            - 지금 플리랑 비슷한 노래 계속 틀어줘 / 추천 그만
            - 볼륨 30으로 하고 두 곡 넘겨줘""";
    static final Set<MusicPlayerClient> RUNNING = ConcurrentHashMap.newKeySet();

    @Override
    public SlashCommandData buildCommand() {
        return Commands.slash(COMMAND_NAME, getDescription())
                .addOptions(new OptionData(OptionType.STRING, REQUEST_ARGUMENT,
                        "하고 싶은 걸 자유롭게 적어주세요 (예: 요즘 유행하는 jpop 틀어줘, 한국 노래 다 빼줘)", true)
                        .setMaxLength(AiCards.MAX_REQUEST_LENGTH));
    }

    @Override
    public void onTriggered(SlashCommandInteractionEvent event) {
        Member member = event.getMember();
        VoiceChannel voiceChannel = MemberUtil.getJoinedVoiceChannel(member);
        if (voiceChannel == null) {
            reject(event, member, "AI 기능을 사용하려면 음성채팅방에 먼저 참여해주세요.", false);
            return;
        }
        MusicPlayerController controller = Main.getLuffia().getMusicPlayerController();
        MusicPlayerClient client = controller.findAvailableClient(voiceChannel);
        if (client == null) {
            reject(event, member, "이 서버의 모든 노래봇이 다른 음성채팅방에서 재생 중입니다. 나중에 다시 시도해주세요.", false);
            return;
        }
        if (!controller.getTranslationClient().isEnabled()) {
            reject(event, member, "AI 서버가 설정되지 않았습니다. TRANSLATOR_URL 에 LLM 서버를 설정해주세요.", false);
            return;
        }
        OptionMapping option = event.getOption(REQUEST_ARGUMENT);
        String request = option == null ? "" : option.getAsString().strip();
        if (request.isEmpty()) {
            event.replyComponents(AiCards.error(member, "요청을 입력해주세요.", EXAMPLES)).useComponentsV2(true).queue();
            return;
        }
        String denied = controller.getAiGuard().tryAcquire(member.getIdLong(), voiceChannel.getGuild().getIdLong(),
                OwnerUtil.isOwner(member));
        if (denied != null) {
            reject(event, member, denied, true);
            return;
        }
        if (!RUNNING.add(client)) {
            reject(event, member, "이미 AI 가 다른 요청을 처리하고 있습니다. 잠시 후 다시 시도해주세요.", false);
            return;
        }
        event.replyComponents(status(member, request, "요청을 이해하고 있습니다...", null)).useComponentsV2(true).queue(
                hook -> TranslationJobs.EXECUTOR.execute(() -> handle(event, hook, member, voiceChannel, request, client, controller)),
                error -> RUNNING.remove(client));
    }

    private static void reject(SlashCommandInteractionEvent event, Member member, String reason, boolean ephemeral) {
        event.replyComponents(AiCards.error(member, "AI 기능을 사용할 수 없습니다.", reason))
                .useComponentsV2(true).setEphemeral(ephemeral).queue();
    }

    private void handle(SlashCommandInteractionEvent event, InteractionHook hook, Member member, VoiceChannel voiceChannel,
                        String request, MusicPlayerClient client, MusicPlayerController controller) {
        long startedAt = System.currentTimeMillis();
        SequentialHookEditor editor = new SequentialHookEditor(hook);
        AiProgressReporter reporter = new AiProgressReporter(editor, controller.getTranslationClient(), controller.getScheduler(),
                "요청을 이해하고 있습니다...", (message, compute) -> status(member, request, message, compute));
        try {
            AiAgent agent = new AiAgent(controller, client, member, event, voiceChannel, reporter);
            AiAgent.Result result = agent.run(request);
            ActionRow buttons = result.pendingRemoval() == null ? null
                    : controller.getAiRemovalConfirmations().register(member.getIdLong(), client, result.pendingRemoval().tracks());
            editor.finish(AiCards.result(member, request, client, resultBody(result), !result.actions().isEmpty(), buttons));
            LOGGER.info("ai request user={} guild={} request='{}' actions={} took={}ms", member.getId(),
                    voiceChannel.getGuild().getId(), request, result.actions(), System.currentTimeMillis() - startedAt);
        } catch (IOException e) {
            LOGGER.warn("ai request failed: {}", request, e);
            editor.finish(AiCards.error(member,
                    "AI 서버에 연결할 수 없습니다.",
                    "AI 서버가 꺼져 있거나 응답이 올바르지 않습니다. 잠시 후 다시 시도해주세요."));
            sendReasonToOwner(hook, member, e);
        } catch (RuntimeException e) {
            LOGGER.warn("ai request failed: {}", request, e);
            editor.finish(AiCards.error(member,
                    "AI 요청을 처리하지 못했습니다.",
                    "처리 중 오류가 발생했습니다. 잠시 후 다시 시도해주세요."));
            sendReasonToOwner(hook, member, e);
        } finally {
            reporter.stop();
            RUNNING.remove(client);
        }
    }

    static void sendReasonToOwner(InteractionHook hook, Member member, Throwable error) {
        if (member == null || !OwnerUtil.isOwner(member)) return;
        StringBuilder reason = new StringBuilder();
        Throwable current = error;
        for (int depth = 0; current != null && depth < MAX_REASON_DEPTH; depth++, current = current.getCause()) {
            if (depth > 0) reason.append("\n<- ");
            reason.append(current.getClass().getSimpleName());
            if (current.getMessage() != null) reason.append(": ").append(current.getMessage());
        }
        String text = reason.toString().replace("```", "'''");
        if (text.length() > MAX_REASON_LENGTH) text = text.substring(0, MAX_REASON_LENGTH);
        hook.sendMessage("실패 사유 (제작자에게만 보입니다)\n```\n" + text + "\n```")
                .setEphemeral(true)
                .queue(null, e -> LOGGER.debug("failed to send failure reason", e));
    }

    private static Container status(Member member, String request, String message, @Nullable String compute) {
        return AiCards.status(member, STATUS_TITLE, request, message, compute);
    }

    static String resultBody(AiAgent.Result result) {
        String reply = DiscordSafe.escaped(result.reply(), AiCards.MAX_REPLY_LENGTH);
        StringBuilder body = new StringBuilder(reply.isBlank()
                ? (result.actions().isEmpty() ? "요청을 처리하지 못했습니다.\n\n" + EXAMPLES : "요청을 처리했습니다.")
                : reply);
        String actions = AiCards.bullets("한 일", result.actions(), AiCards.MAX_ACTION_LENGTH);
        if (!actions.isEmpty()) body.append("\n\n").append(actions);
        return body.toString();
    }

    @Override
    public String getDescription() {
        return "자연어로 곡 추가·제거·셔플, 인기 차트, 연속 추천, 볼륨·스킵 등을 요청합니다";
    }

    @Override
    public String getUsage() {
        return "/" + COMMAND_NAME + " <" + REQUEST_ARGUMENT + ">";
    }
}
