package kr.kro.backas.music.lyrics;

import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import kr.kro.backas.music.MusicEmbeds;
import kr.kro.backas.music.MusicPlayerClient;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.MessageEmbed;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public class LyricsSession {

    private static final Logger LOGGER = LoggerFactory.getLogger(LyricsSession.class);
    public static final long TICK_MS = 200;
    public static final long MIN_EDIT_INTERVAL_MS = 1200;
    public static final long DEFAULT_OFFSET_MS = 600;

    private final MusicPlayerClient client;
    private final AudioTrack track;
    private final Lyrics lyrics;
    private final Message message;
    private final ScheduledExecutorService scheduler;
    private volatile long offsetMs;
    private ScheduledFuture<?> ticker;
    private int shownIndex = -2;
    private long lastEditAt;
    private final AtomicBoolean editInFlight = new AtomicBoolean(false);
    private final AtomicBoolean stopped = new AtomicBoolean(false);

    public LyricsSession(MusicPlayerClient client,
                         AudioTrack track,
                         Lyrics lyrics,
                         Message message,
                         ScheduledExecutorService scheduler,
                         long offsetMs) {
        this.client = client;
        this.track = track;
        this.lyrics = lyrics;
        this.message = message;
        this.scheduler = scheduler;
        this.offsetMs = offsetMs;
    }

    public void start() {
        ticker = scheduler.scheduleAtFixedRate(this::tick, 0, TICK_MS, TimeUnit.MILLISECONDS);
    }

    public void setOffsetMs(long offsetMs) {
        this.offsetMs = offsetMs;
    }

    public long getOffsetMs() {
        return offsetMs;
    }

    public AudioTrack getTrack() {
        return track;
    }

    public boolean isForTrack(AudioTrack other) {
        return other != null && other.getIdentifier().equals(track.getIdentifier());
    }

    public void stop(String reason) {
        if (!stopped.compareAndSet(false, true)) return;
        if (ticker != null) ticker.cancel(false);
        try {
            message.editMessageEmbeds(buildEmbed(track, lyrics, shownIndex, reason)).queue(null, e -> {});
        } catch (RuntimeException e) {
            LOGGER.debug("failed to finalize lyrics message", e);
        }
    }

    private void tick() {
        try {
            if (stopped.get()) return;
            if (!isForTrack(client.getCurrentPlaying())) {
                stop("재생이 끝났습니다");
                return;
            }
            if (client.isPaused()) return;
            long position = (long) client.getRealPositionMs() + offsetMs;
            int index = indexAt(position);
            if (index == shownIndex) return;
            long now = System.currentTimeMillis();
            if (now - lastEditAt < MIN_EDIT_INTERVAL_MS) return;
            if (!editInFlight.compareAndSet(false, true)) return;
            shownIndex = index;
            lastEditAt = now;
            message.editMessageEmbeds(buildEmbed(track, lyrics, index, null)).queue(
                    m -> editInFlight.set(false),
                    e -> {
                        editInFlight.set(false);
                        LOGGER.debug("lyrics edit failed", e);
                    });
        } catch (RuntimeException e) {
            LOGGER.warn("lyrics tick failed", e);
            stop("가사 표시 중 오류가 발생했습니다");
        }
    }

    private int indexAt(long positionMs) {
        List<LyricLine> lines = lyrics.synced();
        int index = -1;
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).timeMs() <= positionMs) index = i;
            else break;
        }
        return index;
    }

    public static MessageEmbed buildEmbed(AudioTrack track, Lyrics lyrics, int index, String footer) {
        List<LyricLine> lines = lyrics.synced();
        String previous = lineText(lines, index - 1);
        String current = lineText(lines, index);
        String next = lineText(lines, index + 1);
        StringBuilder body = new StringBuilder();
        body.append(previous.isBlank() ? "​" : "*" + previous + "*").append("\n\n");
        body.append("**").append(current.isBlank() ? "♪" : current).append("**").append("\n\n");
        body.append(next.isBlank() ? "​" : "*" + next + "*");
        EmbedBuilder builder = new EmbedBuilder()
                .setColor(MusicEmbeds.PRIMARY)
                .setAuthor(track.getInfo().author + " - " + track.getInfo().title, track.getInfo().uri)
                .setThumbnail(MusicEmbeds.thumbnailOf(track))
                .setDescription(body);
        if (footer != null) builder.setFooter(footer);
        return builder.build();
    }

    private static String lineText(List<LyricLine> lines, int index) {
        if (index < 0 || index >= lines.size()) return "";
        String text = lines.get(index).text();
        return text == null ? "" : text;
    }
}
