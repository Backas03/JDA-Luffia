package kr.kro.backas.music;

import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import kr.kro.backas.Main;
import kr.kro.backas.command.music.slash.QueueSlashCommand;
import kr.kro.backas.util.MemberUtil;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.selections.SelectOption;
import net.dv8tion.jda.api.components.selections.StringSelectMenu;
import net.dv8tion.jda.api.entities.channel.concrete.VoiceChannel;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.GenericComponentInteractionCreateEvent;
import net.dv8tion.jda.api.events.interaction.component.StringSelectInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class TrackCardControls extends ListenerAdapter {

    private static final Logger LOGGER = LoggerFactory.getLogger(TrackCardControls.class);
    static final String PREFIX = "card:";
    static final String MENU = "menu";
    static final String PRONUNCIATION = "pron";
    static final String QUEUE = "queue";
    static final String PREVIOUS = "prev";
    static final String PAUSE = "pause";
    static final String NEXT = "next";
    static final String PLACEHOLDER = "곡 조작하기";

    public static ActionRow row(MusicPlayerClient client) {
        String key = client.getMusicBot().getSelfUser().getId() + ":" + client.getGuildId();
        boolean paused = client.isPaused();
        boolean showing = client.isShowingPronunciation();
        StringSelectMenu menu = StringSelectMenu.create(id(MENU, key))
                .setPlaceholder(PLACEHOLDER)
                .addOptions(
                        SelectOption.of(paused ? "다시 재생" : "일시정지", PAUSE)
                                .withDescription(paused ? "멈춘 곡을 이어서 재생해요" : "지금 곡을 잠시 멈춰요")
                                .withEmoji(CardIcons.of(paused ? CardIcons.Glyph.PLAY : CardIcons.Glyph.PAUSE)),
                        SelectOption.of("다음 곡", NEXT)
                                .withDescription("지금 곡을 건너뛰어요")
                                .withEmoji(CardIcons.of(CardIcons.Glyph.NEXT)),
                        SelectOption.of("이전 곡", PREVIOUS)
                                .withDescription(client.hasPreviousTrack() ? "방금 들은 곡을 다시 틀어요" : "아직 이전에 재생한 곡이 없어요")
                                .withEmoji(CardIcons.of(CardIcons.Glyph.PREVIOUS)),
                        SelectOption.of("대기열 보기", QUEUE)
                                .withDescription("나에게만 보여요")
                                .withEmoji(CardIcons.of(CardIcons.Glyph.QUEUE)),
                        SelectOption.of(showing ? "가사발음 끄기" : "가사발음 켜기", PRONUNCIATION)
                                .withDescription("일본어 가사 아래에 한국어 발음을 보여 줘요")
                                .withEmoji(CardIcons.of(CardIcons.Glyph.LYRICS)))
                .build();
        return ActionRow.of(menu);
    }

    static String id(String action, String key) {
        return PREFIX + action + ":" + key;
    }

    @Nullable
    static String[] parse(String componentId) {
        if (!componentId.startsWith(PREFIX)) return null;
        String[] parts = componentId.substring(PREFIX.length()).split(":");
        return parts.length == 3 ? parts : null;
    }

    @Override
    public void onStringSelectInteraction(@NotNull StringSelectInteractionEvent event) {
        String[] parts = parse(event.getComponentId());
        if (parts == null || !MENU.equals(parts[0]) || event.getValues().isEmpty()) return;
        handle(event, event.getValues().get(0), parts[1], parts[2]);
    }

    @Override
    public void onButtonInteraction(@NotNull ButtonInteractionEvent event) {
        String[] parts = parse(event.getComponentId());
        if (parts == null) return;
        handle(event, parts[0], parts[1], parts[2]);
    }

    private static void handle(GenericComponentInteractionCreateEvent event, String action, String botId, String guildId) {
        MusicPlayerClient client = Main.getLuffia().getMusicPlayerController().findClient(botId, guildId);
        AudioTrack current = client == null ? null : client.getCurrentPlaying();
        if (current == null) {
            notice(event, "지금 재생 중인 곡이 없습니다");
            return;
        }
        VoiceChannel joined = client.getJoinedVoiceChannel();
        VoiceChannel listening = event.getMember() == null ? null : MemberUtil.getJoinedVoiceChannel(event.getMember());
        if (joined == null || listening == null || joined.getIdLong() != listening.getIdLong()) {
            notice(event, "노래 봇과 같은 음성채널에 있어야 조작할 수 있습니다");
            client.redrawTrackCard();
            return;
        }
        switch (action) {
            case PRONUNCIATION -> {
                acknowledge(event);
                client.setShowingPronunciation(!client.isShowingPronunciation());
            }
            case QUEUE -> {
                event.deferReply(true).queue(
                        hook -> QueueSlashCommand.sendView(hook, client, current),
                        e -> LOGGER.debug("failed to open the queue from a track card", e));
                client.redrawTrackCard();
            }
            case PREVIOUS -> {
                if (client.playPrevious()) {
                    acknowledge(event);
                } else {
                    notice(event, "이전에 재생한 곡이 없습니다");
                    client.redrawTrackCard();
                }
            }
            case PAUSE -> {
                acknowledge(event);
                if (!client.pause()) client.resume();
            }
            case NEXT -> {
                acknowledge(event);
                client.skip(1);
            }
            default -> acknowledge(event);
        }
    }

    private static void acknowledge(GenericComponentInteractionCreateEvent event) {
        event.deferEdit().queue(null, e -> LOGGER.debug("failed to acknowledge a track card control", e));
    }

    private static void notice(GenericComponentInteractionCreateEvent event, String message) {
        event.reply(message).setEphemeral(true).queue(null, e -> LOGGER.debug("failed to answer a track card control", e));
    }
}
