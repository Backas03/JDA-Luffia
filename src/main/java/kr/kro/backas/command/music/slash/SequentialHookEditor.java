package kr.kro.backas.command.music.slash;

import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.interactions.InteractionHook;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

final class SequentialHookEditor {

    private static final Logger LOGGER = LoggerFactory.getLogger(SequentialHookEditor.class);

    private final InteractionHook hook;
    private CompletableFuture<?> tail = CompletableFuture.completedFuture(null);
    private boolean finished;

    SequentialHookEditor(InteractionHook hook) {
        this.hook = hook;
    }

    synchronized void edit(MessageEmbed embed) {
        if (finished) return;
        enqueue(() -> hook.editOriginalEmbeds(embed).submit());
    }

    synchronized void finish(MessageEmbed embed) {
        if (finished) return;
        finished = true;
        enqueue(() -> hook.editOriginalEmbeds(embed).setComponents().submit());
    }

    synchronized void finish(MessageEmbed embed, ActionRow row) {
        if (finished) return;
        finished = true;
        enqueue(() -> hook.editOriginalEmbeds(embed).setComponents(row).submit());
    }

    private void enqueue(Supplier<CompletableFuture<?>> request) {
        tail = tail.handle((result, error) -> null)
                .thenCompose(ignored -> request.get())
                .whenComplete((result, error) -> {
                    if (error != null) LOGGER.warn("interaction message edit failed", error);
                });
    }
}
