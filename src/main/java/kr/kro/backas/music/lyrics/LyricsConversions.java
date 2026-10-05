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

public class LyricsConversions extends ListenerAdapter {

    private static final Logger LOGGER = LoggerFactory.getLogger(LyricsConversions.class);
    private static final String PREFIX = "lyrics-ai:";
    private static final long EXPIRE_MS = 60 * 60 * 1000;
    public static final String LABEL = "AI 가사 변환";
    public static final String LABEL_RUNNING = "AI 가사 변환 중";

    public interface Convertible {
        boolean isConverting();

        void requestConversion();

        Container render();
    }

    private record Entry(Convertible view, long createdAt) {
    }

    private final Map<String, Entry> entries = new ConcurrentHashMap<>();

    public String register(Convertible view) {
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

    public static Button button(String token, boolean converting) {
        Button button = Button.primary(PREFIX + token, converting ? LABEL_RUNNING : LABEL);
        return converting ? button.asDisabled() : button;
    }

    @Override
    public void onButtonInteraction(@NotNull ButtonInteractionEvent event) {
        String id = event.getComponentId();
        if (!id.startsWith(PREFIX)) return;
        Entry entry = entries.get(id.substring(PREFIX.length()));
        if (entry == null) {
            event.deferEdit().queue(null, e -> LOGGER.debug("failed to acknowledge an expired conversion button", e));
            return;
        }
        Convertible view = entry.view();
        if (!view.isConverting()) view.requestConversion();
        event.editComponents(view.render()).useComponentsV2(true)
                .queue(null, e -> LOGGER.debug("failed to show conversion progress", e));
    }
}
