package kr.kro.backas.music;

import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import com.sedmelluq.discord.lavaplayer.track.AudioTrackInfo;
import kr.kro.backas.Main;
import kr.kro.backas.command.music.slash.AiShuffleSlashCommand;
import kr.kro.backas.command.music.slash.AiSlashCommand;
import kr.kro.backas.command.music.slash.PlaySlashCommand;
import kr.kro.backas.command.music.slash.QueueSlashCommand;
import kr.kro.backas.command.music.slash.VolumeSlashCommand;
import kr.kro.backas.music.ai.AiAutoplay;
import kr.kro.backas.music.filter.ConfiguredEqualizer;
import kr.kro.backas.music.filter.KaraokeMode;
import kr.kro.backas.music.lyrics.LyricsJumps;
import kr.kro.backas.music.lyrics.LyricsPresenter;
import kr.kro.backas.music.lyrics.LyricsSession;
import kr.kro.backas.util.DiscordSafe;
import kr.kro.backas.util.DurationUtil;
import kr.kro.backas.util.MemberUtil;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.components.container.Container;
import net.dv8tion.jda.api.components.container.ContainerChildComponent;
import net.dv8tion.jda.api.components.label.Label;
import net.dv8tion.jda.api.components.selections.SelectOption;
import net.dv8tion.jda.api.components.selections.StringSelectMenu;
import net.dv8tion.jda.api.components.separator.Separator;
import net.dv8tion.jda.api.components.textdisplay.TextDisplay;
import net.dv8tion.jda.api.components.textinput.TextInput;
import net.dv8tion.jda.api.components.textinput.TextInputStyle;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.channel.concrete.VoiceChannel;
import net.dv8tion.jda.api.events.interaction.ModalInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.GenericComponentInteractionCreateEvent;
import net.dv8tion.jda.api.events.interaction.component.StringSelectInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.modals.ModalMapping;
import net.dv8tion.jda.api.modals.Modal;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.List;

public class ControlPanel extends ListenerAdapter {

    private static final Logger LOGGER = LoggerFactory.getLogger(ControlPanel.class);
    static final String PREFIX = "panel:";
    static final String MODAL_PREFIX = "panel-modal:";
    static final String INPUT = "text";
    static final String OPEN = "open";
    static final String CARD_GAP = "gap";
    static final String CARD_OUTRO = "outro";
    static final String PREVIOUS = "prev";
    static final String PAUSE = "pause";
    static final String NEXT = "next";
    static final String BACK = "back10";
    static final String PREVIOUS_LINE = "pline";
    static final String INTERLUDE = "interlude";
    static final String NEXT_LINE = "nline";
    static final String FORWARD = "fwd10";
    static final String VOLUME_DOWN = "vdown";
    static final String VOLUME_UP = "vup";
    static final String EFFECTS = "fx";
    static final String PRONUNCIATION = "pron";
    static final String FULL_LYRICS = "lyrics";
    static final String LYRICS_LATER = "later";
    static final String LYRICS_EARLIER = "earlier";
    static final String QUEUE = "queue";
    static final String SHUFFLE = "shuffle";
    static final String AI_SHUFFLE = "aishuffle";
    static final String REPEAT = "repeat";
    static final String AUTOPLAY = "auto";
    static final String ADD = "add";
    static final String ASK = "ask";
    static final String LEAVE = "leave";
    static final long JUMP_MS = 10_000;
    static final long LYRICS_STEP_MS = 500;
    static final int VOLUME_STEP = 10;
    static final double[] SPEEDS = {0.5, 0.75, 1.0, 1.25, 1.5, 2.0};
    private static final int MAX_TITLE_LENGTH = 80;
    private static final DecimalFormat DECIMAL = new DecimalFormat("0.##");

    public static Button openButton(MusicPlayerClient client) {
        return Button.secondary(id(OPEN, keyOf(client)), "조작").withEmoji(CardIcons.of(CardIcons.Glyph.ADJUST));
    }

    public static Button gapButton(MusicPlayerClient client) {
        return Button.primary(id(CARD_GAP, keyOf(client)), "간주 점프").withEmoji(CardIcons.of(CardIcons.Glyph.NEXT));
    }

    public static Button outroButton(MusicPlayerClient client) {
        return Button.primary(id(CARD_OUTRO, keyOf(client)), "종주 건너뛰기").withEmoji(CardIcons.of(CardIcons.Glyph.NEXT));
    }

    static String keyOf(MusicPlayerClient client) {
        return client.getMusicBot().getSelfUser().getId() + ":" + client.getGuildId();
    }

    static String id(String action, String key) {
        return PREFIX + action + ":" + key;
    }

    @Nullable
    static String[] parse(String componentId, String prefix) {
        if (!componentId.startsWith(prefix)) return null;
        String[] parts = componentId.substring(prefix.length()).split(":");
        return parts.length == 3 ? parts : null;
    }

    public static Container build(MusicPlayerClient client, @Nullable String notice) {
        String key = keyOf(client);
        AudioTrack track = client.getCurrentPlaying();
        List<ContainerChildComponent> children = new ArrayList<>();
        children.add(TextDisplay.of(header(client, track, notice)));
        children.add(Separator.createDivider(Separator.Spacing.SMALL));

        children.add(TextDisplay.of("-# 재생"));
        children.add(ActionRow.of(
                Button.secondary(id(PREVIOUS, key), CardIcons.of(CardIcons.Glyph.PREVIOUS)),
                Button.primary(id(PAUSE, key), CardIcons.of(client.isPaused() ? CardIcons.Glyph.PLAY : CardIcons.Glyph.PAUSE)),
                Button.secondary(id(NEXT, key), CardIcons.of(CardIcons.Glyph.NEXT))));

        children.add(TextDisplay.of("-# 구간 이동"));
        children.add(ActionRow.of(
                Button.secondary(id(BACK, key), "« 10초"),
                Button.secondary(id(PREVIOUS_LINE, key), "‹ 이전 줄"),
                Button.secondary(id(INTERLUDE, key), "간주 점프"),
                Button.secondary(id(NEXT_LINE, key), "다음 줄 ›"),
                Button.secondary(id(FORWARD, key), "10초 »")));

        children.add(TextDisplay.of("-# 소리 · " + soundSummary(client)));
        children.add(ActionRow.of(
                Button.secondary(id(VOLUME_DOWN, key), "−" + VOLUME_STEP).withEmoji(CardIcons.of(CardIcons.Glyph.VOLUME_DOWN)),
                Button.secondary(id(VOLUME_UP, key), "+" + VOLUME_STEP).withEmoji(CardIcons.of(CardIcons.Glyph.VOLUME_UP))));
        children.add(ActionRow.of(effectsMenu(key)));

        children.add(TextDisplay.of("-# 가사 · 싱크 " + signedSeconds(lyricsOffset(client))));
        children.add(ActionRow.of(
                (client.isShowingPronunciation() ? Button.success(id(PRONUNCIATION, key), "가사발음") : Button.secondary(id(PRONUNCIATION, key), "가사발음"))
                        .withEmoji(CardIcons.of(CardIcons.Glyph.LYRICS)),
                Button.secondary(id(FULL_LYRICS, key), "전체 가사").withEmoji(CardIcons.of(CardIcons.Glyph.DOCUMENT)),
                Button.secondary(id(LYRICS_LATER, key), "« 가사 늦게"),
                Button.secondary(id(LYRICS_EARLIER, key), "가사 빠르게 »")));

        children.add(TextDisplay.of("-# 대기열 · " + client.getTrackQueue().size() + "곡"));
        AiAutoplay autoplay = client.getAutoplay();
        children.add(ActionRow.of(
                Button.secondary(id(QUEUE, key), "대기열").withEmoji(CardIcons.of(CardIcons.Glyph.QUEUE)),
                Button.secondary(id(SHUFFLE, key), "셔플").withEmoji(CardIcons.of(CardIcons.Glyph.SHUFFLE)),
                Button.secondary(id(AI_SHUFFLE, key), "AI 셔플").withEmoji(CardIcons.of(CardIcons.Glyph.SPARKLE)),
                (client.getRepeatMode() == RepeatMode.NO_REPEAT ? Button.secondary(id(REPEAT, key), client.getRepeatModeName())
                        : Button.success(id(REPEAT, key), client.getRepeatModeName())).withEmoji(CardIcons.of(CardIcons.Glyph.REPEAT)),
                (autoplay.isEnabled() ? Button.success(id(AUTOPLAY, key), "AI 추천") : Button.secondary(id(AUTOPLAY, key), "AI 추천"))
                        .withEmoji(CardIcons.of(CardIcons.Glyph.SPARKLE))));

        children.add(Separator.createDivider(Separator.Spacing.SMALL));
        children.add(ActionRow.of(
                Button.secondary(id(ADD, key), "곡 추가").withEmoji(CardIcons.of(CardIcons.Glyph.PLUS)),
                Button.secondary(id(ASK, key), "AI에게 부탁").withEmoji(CardIcons.of(CardIcons.Glyph.CHAT)),
                Button.danger(id(LEAVE, key), "나가기").withEmoji(CardIcons.of(CardIcons.Glyph.EXIT))));
        Container container = Container.of(children);
        return track == null ? container : container.withAccentColor(ArtworkColors.of(MusicEmbeds.thumbnailOf(track)));
    }

    private static String header(MusicPlayerClient client, @Nullable AudioTrack track, @Nullable String notice) {
        StringBuilder text = new StringBuilder();
        if (track == null) {
            text.append("**지금 재생 중인 곡이 없어요**");
        } else {
            AudioTrackInfo info = track.getInfo();
            text.append("**").append(DiscordSafe.escaped(info.title, MAX_TITLE_LENGTH)).append("**\n-# ");
            if (info.author != null && !info.author.isBlank()) text.append(DiscordSafe.escaped(info.author, MAX_TITLE_LENGTH)).append(" · ");
            long position = Math.max(0, (long) client.getRealPositionMs());
            text.append(DurationUtil.formatClock(position / 1000));
            if (!info.isStream && info.length > 0) text.append(" / ").append(DurationUtil.formatClock(info.length / 1000));
            text.append(" · ").append(MusicEmbeds.botName(client.getGuild()));
        }
        if (notice != null && !notice.isBlank()) text.append("\n-# ").append(notice);
        return text.toString();
    }

    static String soundSummary(MusicPlayerClient client) {
        List<String> parts = new ArrayList<>();
        parts.add("볼륨 " + client.getVolume() + "%");
        parts.add(DECIMAL.format(client.getCurrentPlaySpeed()) + "배");
        parts.add(client.getCurrentEqualizer().getName());
        parts.add(client.getKaraokeMode().isActive() ? "노래방 " + client.getKaraokeMode().getName() : "노래방 끔");
        return String.join(" · ", parts);
    }

    static String signedSeconds(long ms) {
        if (ms == 0) return "0초";
        return (ms > 0 ? "+" : "−") + DECIMAL.format(Math.abs(ms) / 1000.0) + "초";
    }

    static StringSelectMenu effectsMenu(String key) {
        List<SelectOption> options = new ArrayList<>();
        for (double speed : SPEEDS) options.add(SelectOption.of("속도 " + DECIMAL.format(speed) + "배", "speed:" + speed));
        for (ConfiguredEqualizer equalizer : ConfiguredEqualizer.values()) {
            options.add(SelectOption.of("이퀄라이저 · " + equalizer.getName(), "eq:" + equalizer.name()));
        }
        for (KaraokeMode mode : KaraokeMode.values()) {
            options.add(SelectOption.of("노래방 · " + mode.getName(), "karaoke:" + mode.name()));
        }
        return StringSelectMenu.create(id(EFFECTS, key))
                .setPlaceholder("음향 효과 · 속도, 이퀄라이저, 노래방")
                .addOptions(options)
                .build();
    }

    private static long lyricsOffset(MusicPlayerClient client) {
        LyricsSession session = currentSession(client);
        return session != null ? session.getOffsetMs() : client.getLyricsOffsetMs();
    }

    @Nullable
    private static LyricsSession currentSession(MusicPlayerClient client) {
        LyricsSession session = client.getLyricsSession();
        AudioTrack track = client.getCurrentPlaying();
        return session != null && track != null && session.isForTrack(track) ? session : null;
    }

    @Override
    public void onButtonInteraction(@NotNull ButtonInteractionEvent event) {
        String[] parts = parse(event.getComponentId(), PREFIX);
        if (parts == null) return;
        MusicPlayerClient client = authorized(event, parts[1], parts[2]);
        if (client == null) return;
        if (OPEN.equals(parts[0])) {
            event.replyComponents(build(client, null)).setEphemeral(true).useComponentsV2(true)
                    .queue(null, e -> LOGGER.debug("failed to open the control panel", e));
            return;
        }
        if (CARD_GAP.equals(parts[0]) || CARD_OUTRO.equals(parts[0])) {
            String notice = CARD_GAP.equals(parts[0]) ? jump(client, LyricsJumps.Jump.INTERLUDE, "지금은 간주가 아니에요") : skipOutro(client);
            if (notice.isEmpty()) event.deferEdit().queue(null, e -> LOGGER.debug("failed to acknowledge a gap jump", e));
            else event.reply(notice).setEphemeral(true).queue(null, e -> LOGGER.debug("failed to answer a gap jump", e));
            return;
        }
        String notice;
        try {
            notice = act(event, client, parts[0], parts[1] + ":" + parts[2]);
        } catch (RuntimeException e) {
            LOGGER.warn("control panel action {} failed", parts[0], e);
            notice = "처리하지 못했어요. 잠시 후 다시 시도해 주세요";
        }
        if (notice != null) refresh(event, client, notice.isEmpty() ? null : notice);
    }

    @Override
    public void onStringSelectInteraction(@NotNull StringSelectInteractionEvent event) {
        String[] parts = parse(event.getComponentId(), PREFIX);
        if (parts == null || !EFFECTS.equals(parts[0]) || event.getValues().isEmpty()) return;
        MusicPlayerClient client = authorized(event, parts[1], parts[2]);
        if (client == null) return;
        refresh(event, client, applyEffect(client, event.getValues().get(0)));
    }

    @Override
    public void onModalInteraction(@NotNull ModalInteractionEvent event) {
        String[] parts = parse(event.getModalId(), MODAL_PREFIX);
        if (parts == null) return;
        Member member = event.getMember();
        String text = valueOf(event);
        if (member == null || text == null || text.isBlank()) {
            event.reply("내용을 입력해 주세요").setEphemeral(true).queue();
            return;
        }
        if (ADD.equals(parts[0])) {
            PlaySlashCommand.play(event, member, text.strip(), null);
        } else if (ASK.equals(parts[0])) {
            new AiSlashCommand().request(event, member, text);
        }
    }

    @Nullable
    private static String valueOf(ModalInteractionEvent event) {
        for (ModalMapping mapping : event.getValues()) {
            if (INPUT.equals(mapping.getCustomId())) return mapping.getAsString();
        }
        return null;
    }

    @Nullable
    private static MusicPlayerClient authorized(GenericComponentInteractionCreateEvent event, String botId, String guildId) {
        MusicPlayerClient client = Main.getLuffia().getMusicPlayerController().findClient(botId, guildId);
        if (client == null || client.getCurrentPlaying() == null) {
            event.reply("지금 재생 중인 곡이 없습니다").setEphemeral(true).queue();
            return null;
        }
        VoiceChannel joined = client.getJoinedVoiceChannel();
        VoiceChannel listening = event.getMember() == null ? null : MemberUtil.getJoinedVoiceChannel(event.getMember());
        if (joined == null || listening == null || joined.getIdLong() != listening.getIdLong()) {
            event.reply("노래 봇과 같은 음성채널에 있어야 조작할 수 있습니다").setEphemeral(true).queue();
            return null;
        }
        return client;
    }

    private static void refresh(GenericComponentInteractionCreateEvent event, MusicPlayerClient client, @Nullable String notice) {
        event.editComponents(build(client, notice)).useComponentsV2(true)
                .queue(null, e -> LOGGER.debug("failed to refresh the control panel", e));
    }

    @Nullable
    private static String act(ButtonInteractionEvent event, MusicPlayerClient client, String action, String key) {
        AudioTrack current = client.getCurrentPlaying();
        Member member = event.getMember();
        switch (action) {
            case PREVIOUS:
                return client.playPrevious() ? "" : "이전에 재생한 곡이 없어요";
            case PAUSE:
                if (!client.pause()) client.resume();
                return "";
            case NEXT:
                client.skip(1);
                return "";
            case BACK:
                return client.seekBy(-JUMP_MS) ? "" : "이 곡은 위치를 옮길 수 없어요";
            case FORWARD:
                return client.seekBy(JUMP_MS) ? "" : "이 곡은 위치를 옮길 수 없어요";
            case PREVIOUS_LINE:
                return jump(client, LyricsJumps.Jump.PREVIOUS_LINE, "이전 줄이 없어요");
            case NEXT_LINE:
                return jump(client, LyricsJumps.Jump.NEXT_LINE, "다음 줄이 없어요");
            case INTERLUDE:
                return jump(client, LyricsJumps.Jump.INTERLUDE, "지금은 간주가 아니에요");
            case VOLUME_DOWN:
                client.setVolume(Math.max(VolumeSlashCommand.MIN_VOLUME, client.getVolume() - VOLUME_STEP));
                return "";
            case VOLUME_UP:
                client.setVolume(Math.min(VolumeSlashCommand.MAX_VOLUME, client.getVolume() + VOLUME_STEP));
                return "";
            case PRONUNCIATION:
                client.setShowingPronunciation(!client.isShowingPronunciation());
                return "";
            case FULL_LYRICS:
                long offset = lyricsOffset(client);
                event.deferReply(true).queue(hook -> LyricsPresenter.presentViaHook(client, current, hook, offset, false, member),
                        e -> LOGGER.debug("failed to open full lyrics from the control panel", e));
                return null;
            case LYRICS_LATER:
                return shiftLyrics(client, -LYRICS_STEP_MS);
            case LYRICS_EARLIER:
                return shiftLyrics(client, LYRICS_STEP_MS);
            case QUEUE:
                event.deferReply(true).queue(hook -> QueueSlashCommand.sendView(hook, client, current),
                        e -> LOGGER.debug("failed to open the queue from the control panel", e));
                return null;
            case SHUFFLE:
                int shuffled = client.shuffleQueue();
                return shuffled < 2 ? "셔플하려면 대기열에 2곡 이상 있어야 해요" : "대기열 " + shuffled + "곡을 셔플했어요";
            case AI_SHUFFLE:
                new AiShuffleSlashCommand().shuffle(event, member);
                return null;
            case REPEAT:
                client.setRepeatMode(nextRepeat(client.getRepeatMode()));
                return "";
            case AUTOPLAY:
                AiAutoplay autoplay = client.getAutoplay();
                if (autoplay.isEnabled()) autoplay.stop();
                else autoplay.start(autoplay.getCriteria(), false, member, event);
                return "";
            case ADD:
                event.replyModal(inputModal(ADD, key, "곡 추가", "검색어 또는 링크", "유튜브·스포티파이 링크나 곡 제목", TextInputStyle.SHORT))
                        .queue(null, e -> LOGGER.debug("failed to open the add song form", e));
                return null;
            case ASK:
                event.replyModal(inputModal(ASK, key, "AI에게 부탁", "요청", "예: 신나는 J-pop 다섯 곡 틀어줘", TextInputStyle.PARAGRAPH))
                        .queue(null, e -> LOGGER.debug("failed to open the ai request form", e));
                return null;
            case LEAVE:
                client.disconnectFromVoiceChannelAndResetTrack();
                event.editComponents(Container.of(TextDisplay.of("노래 봇을 내보내고 대기열을 비웠어요"))).useComponentsV2(true)
                        .queue(null, e -> LOGGER.debug("failed to close the control panel", e));
                return null;
            default:
                return "";
        }
    }

    private static String jump(MusicPlayerClient client, LyricsJumps.Jump jump, String nothing) {
        LyricsSession session = currentSession(client);
        if (session == null) return "이 곡은 가사 타이밍이 없어 쓸 수 없어요";
        Long target = session.seekTarget(jump);
        if (target == null) {
            if (jump == LyricsJumps.Jump.INTERLUDE && session.inOutro()) return skipOutro(client);
            return nothing;
        }
        return client.seekTo(target) ? "" : "이 곡은 위치를 옮길 수 없어요";
    }

    private static String skipOutro(MusicPlayerClient client) {
        LyricsSession session = currentSession(client);
        if (session == null || !session.inOutro()) return "지금은 종주가 아니에요";
        client.skip(1);
        return "";
    }

    private static String shiftLyrics(MusicPlayerClient client, long deltaMs) {
        long offset = lyricsOffset(client) + deltaMs;
        LyricsSession session = currentSession(client);
        if (session != null) session.setOffsetMs(offset);
        client.setLyricsOffsetMs(offset);
        return "";
    }

    static RepeatMode nextRepeat(RepeatMode mode) {
        return switch (mode) {
            case NO_REPEAT -> RepeatMode.REPEAT_ALL;
            case REPEAT_ALL -> RepeatMode.REPEAT_CURRENT;
            case REPEAT_CURRENT -> RepeatMode.NO_REPEAT;
        };
    }

    @Nullable
    static String applyEffect(MusicPlayerClient client, String value) {
        String[] parts = value.split(":", 2);
        if (parts.length != 2) return null;
        try {
            switch (parts[0]) {
                case "speed" -> client.setPlaySpeed(Double.parseDouble(parts[1]));
                case "eq" -> client.setEqualizer(ConfiguredEqualizer.valueOf(parts[1]));
                case "karaoke" -> client.setKaraokeMode(KaraokeMode.valueOf(parts[1]));
                default -> {
                    return null;
                }
            }
        } catch (IllegalArgumentException e) {
            return "그 효과는 쓸 수 없어요";
        }
        return null;
    }

    private static Modal inputModal(String action, String key, String title, String label, String placeholder, TextInputStyle style) {
        TextInput input = TextInput.create(INPUT, style).setPlaceholder(placeholder).setRequired(true).setMaxLength(300).build();
        return Modal.create(MODAL_PREFIX + action + ":" + key, title).addComponents(Label.of(label, input)).build();
    }
}
