package kr.kro.backas.command.music.slash;

import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import kr.kro.backas.Main;
import kr.kro.backas.SharedConstant;
import kr.kro.backas.command.api.SlashCommandSource;
import kr.kro.backas.music.MusicEmbeds;
import kr.kro.backas.music.MusicPlayerClient;
import kr.kro.backas.music.MusicPlayerController;
import kr.kro.backas.music.ai.AiFlowShuffle;
import kr.kro.backas.music.ai.AiTrackTagger;
import kr.kro.backas.music.lyrics.TranslationJobs;
import kr.kro.backas.util.MemberUtil;
import kr.kro.backas.util.OwnerUtil;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.channel.concrete.VoiceChannel;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.interactions.InteractionHook;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.interactions.commands.build.SlashCommandData;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

public class AiShuffleSlashCommand implements SlashCommandSource {
    private static final Logger LOGGER = LoggerFactory.getLogger(AiShuffleSlashCommand.class);
    public static final String COMMAND_NAME = "ai셔플";
    private static final int MAX_PREVIEW_ROWS = 5;

    @Override
    public SlashCommandData buildCommand() {
        return Commands.slash(COMMAND_NAME, getDescription());
    }

    @Override
    public void onTriggered(SlashCommandInteractionEvent event) {
        Member member = event.getMember();
        VoiceChannel voiceChannel = MemberUtil.getJoinedVoiceChannel(member);
        if (voiceChannel == null) {
            event.replyEmbeds(MusicEmbeds.error(member,
                    "대기열을 섞을 수 없습니다.",
                    "대기열을 섞으려면 음성채팅방에 먼저 참여해주세요.").build()).queue();
            return;
        }
        MusicPlayerController controller = Main.getLuffia().getMusicPlayerController();
        MusicPlayerClient client = controller.findFromVoiceChannel(voiceChannel);
        if (client == null) {
            event.replyEmbeds(MusicEmbeds.error(member,
                    "대기열을 섞을 수 없습니다.",
                    "해당 채널에서 음악을 재생중인 노래 봇이 없습니다.").build()).queue();
            return;
        }
        if (!controller.getTranslationClient().isEnabled()) {
            event.replyEmbeds(MusicEmbeds.error(member,
                    "대기열을 섞을 수 없습니다.",
                    "AI 서버가 설정되지 않았습니다. TRANSLATOR_URL 에 LLM 서버를 설정해주세요.").build()).queue();
            return;
        }
        int size = client.getTrackQueue().size();
        if (size < 2) {
            event.replyEmbeds(MusicEmbeds.error(member,
                    "대기열을 섞을 수 없습니다.",
                    "대기열에 2곡 이상 있어야 섞을 수 있습니다. (현재 " + size + "곡)").build()).queue();
            return;
        }
        String denied = controller.getAiGuard().tryAcquire(member.getIdLong(), voiceChannel.getGuild().getIdLong(),
                OwnerUtil.isOwner(member));
        if (denied != null) {
            event.replyEmbeds(MusicEmbeds.error(member, "대기열을 섞을 수 없습니다.", denied).build())
                    .setEphemeral(true).queue();
            return;
        }
        if (!AiSlashCommand.RUNNING.add(client)) {
            event.replyEmbeds(MusicEmbeds.error(member,
                    "대기열을 섞을 수 없습니다.",
                    "이미 AI 가 다른 요청을 처리하고 있습니다. 잠시 후 다시 시도해주세요.").build()).queue();
            return;
        }
        event.replyEmbeds(status(member, "대기열을 분석하고 있습니다...").build()).queue(
                hook -> TranslationJobs.EXECUTOR.execute(() -> handle(hook, member, client, controller)),
                error -> AiSlashCommand.RUNNING.remove(client));
    }

    private void handle(InteractionHook hook, Member member, MusicPlayerClient client, MusicPlayerController controller) {
        SequentialHookEditor editor = new SequentialHookEditor(hook);
        AiProgressReporter reporter = new AiProgressReporter(editor, controller.getTranslationClient(), controller.getScheduler(),
                "대기열을 분석하고 있습니다...", (message, compute) -> status(member, message, compute).build());
        long startedAt = System.currentTimeMillis();
        try {
            List<AudioTrack> queue = client.getTrackQueue();
            List<AudioTrack> targets = queue.subList(0, Math.min(queue.size(), AiTrackTagger.MAX_TRACKS));
            Map<AudioTrack, AiTrackTagger.Tag> tags = controller.getAiTrackTagger().tag(targets, done ->
                    reporter.progress("대기열을 분석하고 있습니다", done, targets.size()));
            LOGGER.info("ai shuffle tagged {} track(s) in {}ms", targets.size(), System.currentTimeMillis() - startedAt);
            List<AudioTrack> order = AiFlowShuffle.arrange(targets, tags, ThreadLocalRandom.current());
            List<AudioTrack> rest = new ArrayList<>(queue.subList(targets.size(), queue.size()));
            Collections.shuffle(rest);
            List<AudioTrack> full = new ArrayList<>(order);
            full.addAll(rest);
            int reordered = client.reorderQueue(full);
            LOGGER.info("ai shuffle reordered {} track(s) in {}ms", reordered, System.currentTimeMillis() - startedAt);
            StringBuilder description = new StringBuilder()
                    .append(reordered).append("곡을 비슷한 곡끼리 이어지도록 섞었습니다.\n")
                    .append(AiFlowShuffle.summarize(order, tags));
            if (!rest.isEmpty()) {
                description.append("\n(대기열이 길어 앞 ").append(targets.size()).append("곡만 분석했습니다. 나머지 ")
                        .append(rest.size()).append("곡은 뒤에 무작위로 둡니다)");
            }
            description.append("\n\n**다음 곡**\n").append(MusicEmbeds.queuePreview(client.getTrackQueue(), MAX_PREVIEW_ROWS));
            editor.finish(new EmbedBuilder()
                    .setColor(MusicEmbeds.SUCCESS)
                    .setAuthor(MemberUtil.getName(member))
                    .setTitle("AI 셔플을 완료했습니다")
                    .setDescription(description)
                    .addField("노래 봇", MusicEmbeds.botName(client.getGuild()), true)
                    .setFooter(SharedConstant.RELEASE_VERSION)
                    .build());
        } catch (IOException e) {
            LOGGER.warn("ai shuffle failed", e);
            editor.finish(MusicEmbeds.error(member,
                    "AI 서버에 연결할 수 없습니다.",
                    "AI 서버가 꺼져 있거나 응답이 올바르지 않습니다. 잠시 후 다시 시도해주세요.").build());
            AiSlashCommand.sendReasonToOwner(hook, member, e);
        } catch (RuntimeException e) {
            LOGGER.warn("ai shuffle failed", e);
            editor.finish(MusicEmbeds.error(member,
                    "AI 셔플을 처리하지 못했습니다.",
                    "처리 중 오류가 발생했습니다. 잠시 후 다시 시도해주세요.").build());
            AiSlashCommand.sendReasonToOwner(hook, member, e);
        } finally {
            reporter.stop();
            AiSlashCommand.RUNNING.remove(client);
        }
    }

    private static EmbedBuilder status(Member member, String message) {
        return status(member, message, null);
    }

    private static EmbedBuilder status(Member member, String message, @Nullable String compute) {
        EmbedBuilder builder = new EmbedBuilder()
                .setColor(MusicEmbeds.PRIMARY)
                .setAuthor(MemberUtil.getName(member))
                .setTitle("AI 가 대기열을 섞고 있습니다")
                .setDescription(message);
        if (compute != null) builder.addField(EmbedBuilder.ZERO_WIDTH_SPACE, "-# " + compute, false);
        return builder.setFooter(SharedConstant.RELEASE_VERSION);
    }

    @Override
    public String getDescription() {
        return "AI 가 대기열 곡의 장르·언어·분위기를 파악해 비슷한 곡끼리 이어지도록 섞습니다";
    }

    @Override
    public String getUsage() {
        return "/" + COMMAND_NAME;
    }
}
