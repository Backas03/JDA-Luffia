package kr.kro.backas.music.lyrics;

import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.components.container.Container;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class LyricsExpansions extends ListenerAdapter {

    private static final Logger LOGGER = LoggerFactory.getLogger(LyricsExpansions.class);
    private static final String PREFIX = "lyrics-more:";
    private static final long EXPIRE_MS = 60 * 60 * 1000;

    public interface Expandable {
        boolean isExpanded();

        void setExpanded(boolean expanded);

        Container render();
    }

    private record Entry(Expandable view, long createdAt) {
    }

    private final Map<String, Entry> entries = new ConcurrentHashMap<>();

    public String register(Expandable view) {
        prune();
        String token = UUID.randomUUID().toString();
        entries.put(token, new Entry(view, System.currentTimeMillis()));
        return token;
    }

    public void release(String token) {
        entries.remove(token);
    }

    public void prune() {
        long now = System.currentTimeMillis();
        entries.values().removeIf(entry -> now - entry.createdAt() > EXPIRE_MS);
    }

    public static Button button(String token, boolean expanded) {
        return Button.secondary(PREFIX + token, expanded ? "접기" : "더보기");
    }

    @Override
    public void onButtonInteraction(@NotNull ButtonInteractionEvent event) {
        String id = event.getComponentId();
        if (!id.startsWith(PREFIX)) return;
        Entry entry = entries.get(id.substring(PREFIX.length()));
        if (entry == null) {
            event.deferEdit().queue(null, e -> LOGGER.debug("failed to acknowledge an expired lyrics button", e));
            return;
        }
        Expandable view = entry.view();
        view.setExpanded(!view.isExpanded());
        event.editComponents(view.render()).useComponentsV2(true)
                .queue(null, e -> LOGGER.debug("failed to toggle full lyrics", e));
    }
}
