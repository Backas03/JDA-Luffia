package kr.kro.backas.music;

import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import kr.kro.backas.Main;
import kr.kro.backas.command.music.slash.QueueSlashCommand;
import kr.kro.backas.util.MemberUtil;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.entities.channel.concrete.VoiceChannel;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class TrackCardControls extends ListenerAdapter {

    private static final Logger LOGGER = LoggerFactory.getLogger(TrackCardControls.class);
    static final String PREFIX = "card:";
    static final String PRONUNCIATION = "pron";
    static final String QUEUE = "queue";
    static final String PREVIOUS = "prev";
    static final String PAUSE = "pause";
    static final String NEXT = "next";

    public static ActionRow row(MusicPlayerClient client) {
        String key = client.getMusicBot().getSelfUser().getId() + ":" + client.getGuildId();
        String pronunciationId = id(PRONUNCIATION, key);
        Button pronunciation = client.isShowingPronunciation()
                ? Button.primary(pronunciationId, "가사발음")
                : Button.secondary(pronunciationId, "가사발음");
        return ActionRow.of(
                pronunciation.withEmoji(CardIcons.of(CardIcons.Glyph.LYRICS)),
                Button.secondary(id(QUEUE, key), "대기열").withEmoji(CardIcons.of(CardIcons.Glyph.QUEUE)),
                Button.secondary(id(PREVIOUS, key), CardIcons.of(CardIcons.Glyph.PREVIOUS)).withDisabled(!client.hasPreviousTrack()),
                Button.secondary(id(PAUSE, key), CardIcons.of(client.isPaused() ? CardIcons.Glyph.PLAY : CardIcons.Glyph.PAUSE)),
                Button.secondary(id(NEXT, key), CardIcons.of(CardIcons.Glyph.NEXT)));
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
    public void onButtonInteraction(@NotNull ButtonInteractionEvent event) {
        String[] parts = parse(event.getComponentId());
        if (parts == null) return;
        MusicPlayerClient client = Main.getLuffia().getMusicPlayerController().findClient(parts[1], parts[2]);
        AudioTrack current = client == null ? null : client.getCurrentPlaying();
        if (current == null) {
            notice(event, "지금 재생 중인 곡이 없습니다");
            return;
        }
        VoiceChannel joined = client.getJoinedVoiceChannel();
        VoiceChannel listening = event.getMember() == null ? null : MemberUtil.getJoinedVoiceChannel(event.getMember());
        if (joined == null || listening == null || joined.getIdLong() != listening.getIdLong()) {
            notice(event, "노래 봇과 같은 음성채널에 있어야 조작할 수 있습니다");
            return;
        }
        switch (parts[0]) {
            case PRONUNCIATION -> {
                acknowledge(event);
                client.setShowingPronunciation(!client.isShowingPronunciation());
            }
            case QUEUE -> event.deferReply(true).queue(
                    hook -> QueueSlashCommand.sendView(hook, client, current),
                    e -> LOGGER.debug("failed to open the queue from a track card", e));
            case PREVIOUS -> {
                if (client.playPrevious()) acknowledge(event);
                else notice(event, "이전에 재생한 곡이 없습니다");
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

    private static void acknowledge(ButtonInteractionEvent event) {
        event.deferEdit().queue(null, e -> LOGGER.debug("failed to acknowledge a track card button", e));
    }

    private static void notice(ButtonInteractionEvent event, String message) {
        event.reply(message).setEphemeral(true).queue(null, e -> LOGGER.debug("failed to answer a track card button", e));
    }
}
