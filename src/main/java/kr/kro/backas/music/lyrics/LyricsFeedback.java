package kr.kro.backas.music.lyrics;

import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import com.sedmelluq.discord.lavaplayer.track.AudioTrackInfo;
import kr.kro.backas.music.cache.DiskCache;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.components.buttons.ButtonStyle;
import net.dv8tion.jda.api.components.replacer.ComponentReplacer;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.entities.emoji.Emoji;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public class LyricsFeedback extends ListenerAdapter {

    public record Vote(String vote, String userName, long at) {
    }

    public record Report(String identifier, String title, String author, String uri, String guildId,
                         String translationModel, long finishedAt, Map<String, Vote> votes) {
    }

    public static final String QUESTION = "번역 품질이 어땠나요?";
    static final String PREFIX = "lyrics-feedback:";
    static final String GOOD = "good";
    static final String BAD = "bad";
    private static final int REMEMBERED_SONGS = 500;
    private static final Logger LOGGER = LoggerFactory.getLogger(LyricsFeedback.class);

    private final DiskCache cache;
    private final Map<String, Boolean> latest = Collections.synchronizedMap(new LinkedHashMap<>(64, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
            return size() > REMEMBERED_SONGS;
        }
    });

    public LyricsFeedback() {
        this(DiskCache.defaultCache());
    }

    LyricsFeedback(DiskCache cache) {
        this.cache = cache;
    }

    public static boolean asksAbout(@Nullable String lyricsSource) {
        return Lyrics.Source.AI.label().equals(lyricsSource);
    }

    public static String keyOf(AudioTrack track) {
        return TranslationJobs.sha256(track.getIdentifier()).substring(0, 16);
    }

    public ActionRow buttons(AudioTrack track) {
        String key = keyOf(track);
        Boolean good = latest.get(key);
        return ActionRow.of(button(GOOD, key, good), button(BAD, key, good));
    }

    static Button button(String vote, String key, @Nullable Boolean latestGood) {
        boolean goodButton = GOOD.equals(vote);
        Button button = Button.secondary(PREFIX + vote + ":" + key, Emoji.fromUnicode(goodButton ? "👍" : "👎"));
        if (latestGood == null || latestGood != goodButton) return button;
        return button.withStyle(goodButton ? ButtonStyle.SUCCESS : ButtonStyle.DANGER).asDisabled();
    }

    public void forget(AudioTrack track) {
        latest.remove(keyOf(track));
    }

    public synchronized void open(AudioTrack track, String guildId, String translationModel, long finishedAt) {
        String key = keyOf(track);
        Report existing = cache.read(pathOf(key), Report.class);
        AudioTrackInfo info = track.getInfo();
        Map<String, Vote> votes = existing == null || existing.votes() == null ? new LinkedHashMap<>() : existing.votes();
        cache.write(pathOf(key), new Report(track.getIdentifier(), info.title, info.author, info.uri, guildId,
                translationModel, finishedAt, votes));
    }

    @Nullable
    synchronized Report record(String key, String userId, String userName, boolean good, long now) {
        Report report = cache.read(pathOf(key), Report.class);
        if (report == null) return null;
        Map<String, Vote> votes = report.votes() == null ? new LinkedHashMap<>() : new LinkedHashMap<>(report.votes());
        votes.put(userId, new Vote(good ? GOOD : BAD, userName, now));
        Report updated = new Report(report.identifier(), report.title(), report.author(), report.uri(), report.guildId(),
                report.translationModel(), report.finishedAt(), votes);
        cache.write(pathOf(key), updated);
        latest.put(key, good);
        return updated;
    }

    static String pathOf(String key) {
        return "lyrics-feedback/" + DiskCache.safeName(key) + ".json";
    }

    @Nullable
    static String voteOf(@Nullable String componentId, String key) {
        if (componentId == null || !componentId.startsWith(PREFIX) || !componentId.endsWith(":" + key)) return null;
        String vote = componentId.substring(PREFIX.length(), componentId.length() - key.length() - 1);
        return GOOD.equals(vote) || BAD.equals(vote) ? vote : null;
    }

    @Override
    public void onButtonInteraction(@NotNull ButtonInteractionEvent event) {
        String id = event.getComponentId();
        if (!id.startsWith(PREFIX)) return;
        String[] parts = id.substring(PREFIX.length()).split(":", 2);
        if (parts.length != 2) return;
        boolean good = GOOD.equals(parts[0]);
        String key = parts[1];
        User user = event.getUser();
        Report report = record(key, user.getId(), user.getName(), good, System.currentTimeMillis());
        if (report == null) {
            event.reply("이 곡의 의견을 받을 수 없습니다").setEphemeral(true)
                    .queue(null, e -> LOGGER.debug("failed to answer lyrics feedback", e));
            return;
        }
        LOGGER.info("lyrics feedback {} from {} ({}) for {} ({})", good ? GOOD : BAD, user.getName(), user.getId(),
                report.title(), report.identifier());
        ComponentReplacer restyle = ComponentReplacer.of(Button.class,
                button -> voteOf(button.getCustomId(), key) != null,
                button -> button(voteOf(button.getCustomId(), key), key, good));
        String thanks = "의견 고마워요! " + (good ? "👍 좋아요" : "👎 나빠요") + "로 기록했어요"
                + (good ? ". 나빠요로 바꾸려면 👎를 눌러 주세요" : ". 좋아요로 바꾸려면 👍를 눌러 주세요");
        event.editComponents(event.getMessage().getComponentTree().replace(restyle)).useComponentsV2(true).queue(
                hook -> hook.sendMessage(thanks).setEphemeral(true)
                        .queue(null, e -> LOGGER.debug("failed to thank for lyrics feedback", e)),
                e -> LOGGER.debug("failed to update lyrics feedback buttons", e));
    }
}
