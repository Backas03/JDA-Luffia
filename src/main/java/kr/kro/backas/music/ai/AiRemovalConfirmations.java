package kr.kro.backas.music.ai;

import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import kr.kro.backas.music.MusicPlayerClient;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class AiRemovalConfirmations extends ListenerAdapter {

    private static final Logger LOGGER = LoggerFactory.getLogger(AiRemovalConfirmations.class);
    private static final String PREFIX = "ai-remove:";
    private static final long EXPIRE_MS = 2 * 60 * 1000;

    private record Pending(long requesterId, MusicPlayerClient client, Set<AudioTrack> tracks, long createdAt) {
    }

    private final Map<String, Pending> pending = new ConcurrentHashMap<>();

    public ActionRow register(long requesterId, MusicPlayerClient client, Set<AudioTrack> tracks) {
        long now = System.currentTimeMillis();
        pending.values().removeIf(entry -> now - entry.createdAt() > EXPIRE_MS);
        String token = UUID.randomUUID().toString();
        pending.put(token, new Pending(requesterId, client, tracks, now));
        return ActionRow.of(
                Button.danger(PREFIX + token + ":yes", tracks.size() + "곡 제거"),
                Button.secondary(PREFIX + token + ":no", "취소"));
    }

    @Override
    public void onButtonInteraction(@NotNull ButtonInteractionEvent event) {
        String id = event.getComponentId();
        if (!id.startsWith(PREFIX)) return;
        String[] parts = id.substring(PREFIX.length()).split(":");
        if (parts.length != 2) return;
        Pending entry = pending.get(parts[0]);
        if (entry == null || System.currentTimeMillis() - entry.createdAt() > EXPIRE_MS) {
            pending.remove(parts[0]);
            event.editMessageEmbeds(withNote(event, "확인 시간이 지나서 제거하지 않았습니다.")).setComponents().queue();
            return;
        }
        if (event.getUser().getIdLong() != entry.requesterId()) {
            event.reply("요청한 사람만 누를 수 있습니다.").setEphemeral(true).queue();
            return;
        }
        pending.remove(parts[0]);
        if ("yes".equals(parts[1])) {
            int removed = entry.client().removeFromQueue(entry.tracks());
            LOGGER.info("ai removal confirmed by {}: {} track(s)", event.getUser().getId(), removed);
            event.editMessageEmbeds(withNote(event, removed + "곡을 대기열에서 제거했습니다.")).setComponents().queue();
        } else {
            event.editMessageEmbeds(withNote(event, "제거를 취소했습니다.")).setComponents().queue();
        }
    }

    private static List<MessageEmbed> withNote(ButtonInteractionEvent event, String note) {
        List<MessageEmbed> embeds = event.getMessage().getEmbeds();
        if (embeds.isEmpty()) return List.of(new EmbedBuilder().setDescription(note).build());
        EmbedBuilder builder = new EmbedBuilder(embeds.get(0));
        builder.appendDescription("\n\n**→ " + note + "**");
        return List.of(builder.build());
    }
}
