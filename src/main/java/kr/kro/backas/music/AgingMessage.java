package kr.kro.backas.music;

import net.dv8tion.jda.api.components.container.Container;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.requests.restaction.MessageCreateAction;
import net.dv8tion.jda.api.utils.FileUpload;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

public final class AgingMessage {

    static final long REPOST_AGE_MS = 55L * 60 * 1000;

    private static final Logger LOGGER = LoggerFactory.getLogger(AgingMessage.class);

    private final Supplier<List<FileUpload>> files;
    private volatile CompletableFuture<Message> current;
    private volatile boolean repostFailed;

    public AgingMessage(CompletableFuture<Message> message) {
        this(message, List::of);
    }

    public AgingMessage(CompletableFuture<Message> message, Supplier<List<FileUpload>> files) {
        this.current = message;
        this.files = files;
    }

    public CompletableFuture<Message> current() {
        return current;
    }

    public CompletableFuture<Message> edit(Container view, long deadlineAt) {
        return current.thenCompose(target -> {
            if (!repostFailed && isAging(target.getTimeCreated().toInstant().toEpochMilli(), System.currentTimeMillis())) {
                return repost(target, view);
            }
            return target.editMessageComponents(view).useComponentsV2(true).deadline(deadlineAt).submit()
                    .exceptionallyCompose(error -> movedFrom(target) && TrackCard.isGone(error)
                            ? edit(view, deadlineAt)
                            : CompletableFuture.failedFuture(error));
        });
    }

    static boolean isAging(long createdAt, long now) {
        return now - createdAt >= REPOST_AGE_MS;
    }

    private boolean movedFrom(Message target) {
        return current.getNow(target) != target;
    }

    private synchronized CompletableFuture<Message> repost(Message stale, Container view) {
        CompletableFuture<Message> latest = current;
        if (latest.getNow(null) != stale) {
            return latest.thenCompose(fresh -> fresh.editMessageComponents(view).useComponentsV2(true).submit());
        }
        CompletableFuture<Message> next = new CompletableFuture<>();
        current = next;
        MessageCreateAction action = stale.getChannel().sendMessageComponents(view).useComponentsV2(true);
        List<FileUpload> uploads = files.get();
        if (!uploads.isEmpty()) action = action.addFiles(uploads);
        action.submit().whenComplete((fresh, error) -> {
            if (error != null) {
                LOGGER.warn("could not post message {} again before it turned an hour old, editing it in place", stale.getId(), error);
                repostFailed = true;
                next.complete(stale);
                return;
            }
            LOGGER.info("message {} is about to turn an hour old, posted it again as {}", stale.getId(), fresh.getId());
            next.complete(fresh);
            stale.delete().queue(null, deleteError -> LOGGER.debug("failed to delete the old message {}", stale.getId(), deleteError));
        });
        return next.thenCompose(target -> target == stale
                ? stale.editMessageComponents(view).useComponentsV2(true).submit()
                : CompletableFuture.completedFuture(target));
    }
}
