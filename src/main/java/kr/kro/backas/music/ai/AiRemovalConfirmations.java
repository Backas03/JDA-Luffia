package kr.kro.backas.music.ai;

import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import kr.kro.backas.music.MusicPlayerClient;
import net.dv8tion.jda.api.components.Component;
import net.dv8tion.jda.api.components.MessageTopLevelComponent;
import net.dv8tion.jda.api.components.MessageTopLevelComponentUnion;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.components.container.Container;
import net.dv8tion.jda.api.components.container.ContainerChildComponent;
import net.dv8tion.jda.api.components.container.ContainerChildComponentUnion;
import net.dv8tion.jda.api.components.textdisplay.TextDisplay;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
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
            settle(event, "확인 시간이 지나서 제거하지 않았습니다.");
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
            settle(event, removed + "곡을 대기열에서 제거했습니다.");
        } else {
            settle(event, "제거를 취소했습니다.");
        }
    }

    private static void settle(ButtonInteractionEvent event, String note) {
        event.editComponents(withNote(event.getMessage().getComponents(), note)).useComponentsV2(true).queue();
    }

    static List<MessageTopLevelComponent> withNote(List<MessageTopLevelComponentUnion> components, String note) {
        List<MessageTopLevelComponent> out = new ArrayList<>();
        for (MessageTopLevelComponentUnion top : components) {
            if (top.getType() == Component.Type.ACTION_ROW) continue;
            if (top.getType() != Component.Type.CONTAINER) {
                out.add(top);
                continue;
            }
            Container container = top.asContainer();
            List<ContainerChildComponent> children = new ArrayList<>();
            for (ContainerChildComponentUnion child : container.getComponents()) {
                if (child.getType() != Component.Type.ACTION_ROW) children.add(child);
            }
            children.add(TextDisplay.of("**→ " + note + "**"));
            out.add(Container.of(children).withAccentColor(container.getAccentColorRaw()));
        }
        if (out.isEmpty()) out.add(TextDisplay.of(note));
        return out;
    }
}
