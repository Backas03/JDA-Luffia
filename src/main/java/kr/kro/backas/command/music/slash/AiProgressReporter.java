package kr.kro.backas.command.music.slash;

import kr.kro.backas.Main;
import kr.kro.backas.music.ai.AiAgent;
import kr.kro.backas.music.lyrics.TranslationClient;
import net.dv8tion.jda.api.components.container.Container;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.text.DecimalFormat;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BiFunction;

final class AiProgressReporter implements AiAgent.StatusListener {

    private static final Logger LOGGER = LoggerFactory.getLogger(AiProgressReporter.class);
    static final long INTERVAL_SECONDS = 10;

    private final SequentialHookEditor editor;
    private final TranslationClient translator;
    private final BiFunction<String, String, Container> statusView;
    private final long startedAt = System.currentTimeMillis();
    private final ScheduledFuture<?> ticker;
    private volatile String message;
    private volatile int done = -1;
    private volatile int total = -1;

    AiProgressReporter(SequentialHookEditor editor, TranslationClient translator, ScheduledExecutorService scheduler,
                       String initialMessage, BiFunction<String, String, Container> statusView) {
        this.editor = editor;
        this.translator = translator;
        this.statusView = statusView;
        this.message = initialMessage;
        this.ticker = scheduler.scheduleAtFixedRate(this::render, INTERVAL_SECONDS, INTERVAL_SECONDS, TimeUnit.SECONDS);
    }

    @Override
    public void status(String message) {
        this.message = message;
        this.done = -1;
        this.total = -1;
    }

    @Override
    public void progress(String message, int done, int total) {
        this.message = message;
        this.done = done;
        this.total = total;
    }

    void stop() {
        ticker.cancel(false);
    }

    private void render() {
        try {
            editor.edit(statusView.apply(message, computeLine()));
        } catch (RuntimeException e) {
            LOGGER.debug("failed to render ai progress", e);
        }
    }

    private String computeLine() {
        return computeLine(Main.getLuffia().getMusicPlayerController().getAiGuard().isDebug() ? translator.computeSummary() : null,
                done, total, (System.currentTimeMillis() - startedAt) / 1000);
    }

    static String computeLine(@Nullable String summary, int done, int total, long elapsedSeconds) {
        StringBuilder line = new StringBuilder();
        if (summary != null && !summary.isBlank()) line.append(summary).append(" | ");
        if (total > 0) {
            line.append(new DecimalFormat("0.00").format(100.0 * done / total))
                    .append("% (").append(done).append("/").append(total).append(") · ");
        }
        return line.append(elapsedSeconds).append("초 경과").toString();
    }
}
