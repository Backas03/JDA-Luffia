package kr.kro.backas.command.music.slash;

import kr.kro.backas.config.Config;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import kr.kro.backas.music.MusicEmbeds;
import kr.kro.backas.music.MusicPlayerClient;
import kr.kro.backas.util.DiscordSafe;
import kr.kro.backas.util.MemberUtil;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.container.Container;
import net.dv8tion.jda.api.components.container.ContainerChildComponent;
import net.dv8tion.jda.api.components.separator.Separator;
import net.dv8tion.jda.api.components.textdisplay.TextDisplay;
import net.dv8tion.jda.api.entities.Member;
import org.jetbrains.annotations.Nullable;

import java.awt.Color;
import java.util.ArrayList;
import java.util.List;

final class AiCards {

    static final int MAX_REQUEST_LENGTH = 200;
    static final int MAX_STATUS_LENGTH = 200;
    static final int MAX_REPLY_LENGTH = 1800;
    static final int MAX_ACTION_LENGTH = 150;
    static final int MAX_PREVIEW_ROWS = 5;

    private AiCards() {
    }

    static Container status(Member member, String title, @Nullable String request, String message, @Nullable String compute) {
        List<ContainerChildComponent> children = new ArrayList<>();
        children.add(TextDisplay.of(heading(title, request)));
        children.add(divider());
        children.add(TextDisplay.of(DiscordSafe.escaped(message, MAX_STATUS_LENGTH) + smallLines(compute)));
        children.add(divider());
        children.add(TextDisplay.of(footer(member, null)));
        return Container.of(children).withAccentColor(MusicEmbeds.PRIMARY);
    }

    static Container result(Member member, String request, MusicPlayerClient client, String body, boolean changed,
                            @Nullable ActionRow buttons) {
        List<ContainerChildComponent> children = new ArrayList<>();
        children.add(TextDisplay.of(heading("AI 요청 결과", request)));
        children.add(divider());
        children.add(TextDisplay.of(body));
        String playback = playback(client);
        if (!playback.isEmpty()) {
            children.add(divider());
            children.add(TextDisplay.of(playback));
        }
        if (buttons != null) children.add(buttons);
        children.add(divider());
        children.add(TextDisplay.of(footer(member, MusicEmbeds.botName(client.getGuild()))));
        return Container.of(children).withAccentColor(changed ? MusicEmbeds.SUCCESS : MusicEmbeds.PRIMARY);
    }

    static Container done(Member member, MusicPlayerClient client, String title, String body, Color accent) {
        List<ContainerChildComponent> children = new ArrayList<>();
        children.add(TextDisplay.of("### " + title));
        children.add(divider());
        children.add(TextDisplay.of(body));
        String playback = playback(client);
        if (!playback.isEmpty()) {
            children.add(divider());
            children.add(TextDisplay.of(playback));
        }
        children.add(divider());
        children.add(TextDisplay.of(footer(member, MusicEmbeds.botName(client.getGuild()))));
        return Container.of(children).withAccentColor(accent);
    }

    static Container error(@Nullable Member member, String title, @Nullable String description) {
        List<ContainerChildComponent> children = new ArrayList<>();
        children.add(TextDisplay.of("### " + title + (description == null || description.isBlank() ? "" : "\n" + description)));
        children.add(divider());
        children.add(TextDisplay.of(member == null ? "-# " + Config.get().bot().version() : footer(member, null)));
        return Container.of(children).withAccentColor(MusicEmbeds.ERROR);
    }

    static String heading(String title, @Nullable String request) {
        return "### " + title + (request == null ? "" : "\n" + quote(request));
    }

    static String quote(String request) {
        return "> " + DiscordSafe.escaped(request, MAX_REQUEST_LENGTH).replace("\n", "\n> ");
    }

    static String smallLines(@Nullable String text) {
        if (text == null || text.isBlank()) return "";
        StringBuilder lines = new StringBuilder();
        for (String line : text.split("\n")) {
            lines.append('\n');
            if (!line.isBlank()) lines.append("-# ").append(line);
        }
        return lines.toString();
    }

    static String bullets(String title, List<String> items, int maxLength) {
        if (items.isEmpty()) return "";
        StringBuilder text = new StringBuilder("**").append(title).append("**");
        for (String item : items) text.append("\n- ").append(DiscordSafe.escaped(item, maxLength));
        return text.toString();
    }

    static String playback(MusicPlayerClient client) {
        StringBuilder text = new StringBuilder();
        AudioTrack current = client.getCurrentPlaying();
        if (current != null) text.append("**지금 재생** ").append(MusicEmbeds.titleLink(current.getInfo()));
        List<AudioTrack> queue = client.getTrackQueue();
        if (!queue.isEmpty()) {
            if (!text.isEmpty()) text.append('\n');
            text.append("**다음 곡**\n").append(MusicEmbeds.queuePreview(queue, MAX_PREVIEW_ROWS).stripTrailing());
        }
        return text.toString();
    }

    static String footer(Member member, @Nullable String botName) {
        StringBuilder text = new StringBuilder("-# ").append(DiscordSafe.escaped(MemberUtil.getName(member), 40));
        if (botName != null) text.append(" · 노래 봇 ").append(botName);
        return text.append(" · ").append(Config.get().bot().version()).toString();
    }

    private static Separator divider() {
        return Separator.createDivider(Separator.Spacing.SMALL);
    }
}
