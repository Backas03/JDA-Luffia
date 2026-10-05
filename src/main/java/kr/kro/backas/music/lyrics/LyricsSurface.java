package kr.kro.backas.music.lyrics;

import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import kr.kro.backas.music.TrackCard;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.container.Container;
import net.dv8tion.jda.api.components.textdisplay.TextDisplay;
import net.dv8tion.jda.api.entities.Message;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.concurrent.CompletableFuture;

interface LyricsSurface {

    long channelId();

    boolean showsSong();

    Container frame(TextDisplay body, @Nullable ActionRow actions);

    default Container frame(TextDisplay body) {
        return frame(body, null);
    }

    @Nullable
    default TrackCard card() {
        return null;
    }

    CompletableFuture<?> edit(Container view, long deadlineAt);

    void dismiss();

    static LyricsSurface ofMessage(Message message, AudioTrack track) {
        return new MessageSurface(message, track);
    }

    static LyricsSurface ofCard(TrackCard card) {
        return new CardSurface(card);
    }

    final class MessageSurface implements LyricsSurface {
        private final Message message;
        private final AudioTrack track;

        private MessageSurface(Message message, AudioTrack track) {
            this.message = message;
            this.track = track;
        }

        @Override
        public long channelId() {
            return message.getChannel().getIdLong();
        }

        @Override
        public boolean showsSong() {
            return true;
        }

        @Override
        public Container frame(TextDisplay body, @Nullable ActionRow actions) {
            return LyricsSession.hookFrame(track, body);
        }

        @Override
        public CompletableFuture<?> edit(Container view, long deadlineAt) {
            return message.editMessageComponents(view).useComponentsV2(true).deadline(deadlineAt).submit();
        }

        @Override
        public void dismiss() {
            LyricsSession.deleteMessage(message);
        }
    }

    final class CardSurface implements LyricsSurface {
        private final TrackCard card;

        private CardSurface(TrackCard card) {
            this.card = card;
        }

        @Override
        public long channelId() {
            return card.channelId();
        }

        @Override
        public boolean showsSong() {
            return false;
        }

        @Override
        public Container frame(TextDisplay body, @Nullable ActionRow actions) {
            return card.frame(actions == null ? List.of(body) : List.of(body, actions));
        }

        @Override
        public TrackCard card() {
            return card;
        }

        @Override
        public CompletableFuture<?> edit(Container view, long deadlineAt) {
            if (card.isClosed()) return CompletableFuture.completedFuture(null);
            return card.edit(view, deadlineAt);
        }

        @Override
        public void dismiss() {
            card.close();
        }
    }
}
