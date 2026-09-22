package kr.kro.backas.music;

import com.github.topi314.lavasrc.ExtendedAudioPlaylist;
import com.sedmelluq.discord.lavaplayer.player.AudioLoadResultHandler;
import com.sedmelluq.discord.lavaplayer.tools.FriendlyException;
import com.sedmelluq.discord.lavaplayer.track.AudioPlaylist;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import com.sedmelluq.discord.lavaplayer.track.AudioTrackInfo;
import kr.kro.backas.util.MemberUtil;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.channel.concrete.VoiceChannel;
import net.dv8tion.jda.api.interactions.InteractionHook;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.selections.StringSelectMenu;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

public class MusicLoader implements AudioLoadResultHandler {
    private static final Logger LOGGER = LoggerFactory.getLogger(MusicLoader.class);

    public static final String PLAYLIST_STRING_SELECT_MENU_ID = "music:playlist_selection";
    public static final int MAX_LOADING_MUSIC_RETRY_ATTEMPT = 3;
    public static final int MAX_SELECT_MENU_OPTIONS = 25;
    public static final int MAX_PLAYLIST_ENQUEUE = 1000;
    private static final int SELECT_MENU_TEXT_LIMIT = 100;

    private final MusicPlayerController controller;
    private final MusicSearchQueryInfo queryInfo;
    private final MusicPlayerClient musicPlayerClient;
    private final InteractionHook hook;
    private volatile Map<String, AudioTrack> loadedTracks;
    private int retryAttempt;

    public MusicLoader(MusicPlayerController controller, MusicPlayerClient musicPlayerClient, MusicSearchQueryInfo queryInfo) {
        this.controller = controller;
        this.queryInfo = queryInfo;
        this.musicPlayerClient = musicPlayerClient;
        this.retryAttempt = 0;
        this.hook = queryInfo.getSlashCommandInteractionEvent().deferReply().complete();
    }

    public void loadMusic() {
        this.musicPlayerClient
                .getAudioPlayerManager()
                .loadItem(queryInfo.getQueryWithIdentifier(), this);
    }

    public MusicSearchQueryInfo getQueryInfo() {
        return queryInfo;
    }

    public Map<String, AudioTrack> getLoadedTracks() {
        return loadedTracks;
    }

    public boolean isTrackLoaded() {
        return loadedTracks != null;
    }

    private Member requester() {
        return queryInfo.getRequestedMember();
    }

    @Override
    public void trackLoaded(AudioTrack track) {
        controller.expireSearchData(requester());
        VoiceChannel channel = MemberUtil.getJoinedVoiceChannel(requester());
        if (channel == null) {
            replyNotInVoiceChannel();
            return;
        }
        EmbedBuilder result = musicPlayerClient.enqueue(new MusicSelection(queryInfo, track), channel);
        hook.editOriginalEmbeds(result.build()).queue();
    }

    @Override
    public void playlistLoaded(AudioPlaylist playlist) {
        if (playlist.isSearchResult()) {
            showSearchResults(playlist);
            return;
        }
        enqueuePlaylist(playlist);
    }

    private void showSearchResults(AudioPlaylist playlist) {
        List<AudioTrack> tracks = playlist.getTracks();
        StringSelectMenu.Builder builder = StringSelectMenu.create(PLAYLIST_STRING_SELECT_MENU_ID);
        Map<String, AudioTrack> options = new HashMap<>();
        int count = Math.min(tracks.size(), MAX_SELECT_MENU_OPTIONS);
        for (int i = 0; i < count; i++) {
            AudioTrack track = tracks.get(i);
            AudioTrackInfo info = track.getInfo();
            String value = String.valueOf(i);
            String description = (info.author == null || info.author.isBlank() ? "" : info.author + " · ")
                    + MusicEmbeds.durationOf(info);
            builder.addOption(truncate(info.title), value, truncate(description));
            options.put(value, track);
        }
        this.loadedTracks = options;
        hook.editOriginal("다음은 ``" + queryInfo.getQuery() + "`` 에 대한 "
                        + queryInfo.getIdentifier().getDisplayName() + " 검색 결과입니다. (" + count + "개)\n"
                        + "재생할 곡을 선택해주세요.")
                .setEmbeds()
                .setComponents(ActionRow.of(builder.build()))
                .queue();
    }

    private void enqueuePlaylist(AudioPlaylist playlist) {
        controller.expireSearchData(requester());
        VoiceChannel channel = MemberUtil.getJoinedVoiceChannel(requester());
        if (channel == null) {
            replyNotInVoiceChannel();
            return;
        }

        AudioTrack selected = playlist.getSelectedTrack();
        if (selected != null) {
            EmbedBuilder result = musicPlayerClient.enqueue(new MusicSelection(queryInfo, selected), channel);
            hook.editOriginalEmbeds(result.build()).queue();
            return;
        }
        List<AudioTrack> tracks = playlist.getTracks();
        if (tracks.isEmpty()) {
            noMatches();
            return;
        }
        int limit = Math.min(tracks.size(), MAX_PLAYLIST_ENQUEUE);
        List<AudioTrack> added = new ArrayList<>(limit);
        boolean startedPlaying = false;
        for (int i = 0; i < limit; i++) {
            AudioTrack track = tracks.get(i);
            boolean enqueued = musicPlayerClient.enqueueOrPlay(new MusicSelection(queryInfo, track), channel);
            if (!enqueued) startedPlaying = true;
            added.add(track);
        }
        int total = tracks.size();
        if (playlist instanceof ExtendedAudioPlaylist extended && extended.getTotalTracks() != null) {
            total = Math.max(total, extended.getTotalTracks());
        }
        EmbedBuilder result = MusicEmbeds.playlistEnqueued(
                playlist, added, total, startedPlaying, musicPlayerClient.getGuild(), requester());
        hook.editOriginalEmbeds(result.build()).queue();
    }

    @Override
    public void noMatches() {
        controller.expireSearchData(requester());
        hook.editOriginalEmbeds(MusicEmbeds.error(requester(),
                "검색 데이터가 존재하지 않습니다",
                queryInfo.getQuery()).build()).queue();
    }

    @Override
    public void loadFailed(FriendlyException exception) {
        LOGGER.warn("music load failed. query={}, severity={}",
                queryInfo.getQueryWithIdentifier(), exception.severity, exception);
        String reason = exception.getMessage() == null ? "" : exception.getMessage();

        boolean giveUp = exception.severity == FriendlyException.Severity.COMMON
                || ++retryAttempt >= MAX_LOADING_MUSIC_RETRY_ATTEMPT;
        if (giveUp) {
            controller.expireSearchData(requester());
            hook.editOriginalEmbeds(MusicEmbeds.error(requester(),
                    "음악을 불러오지 못했습니다",
                    (reason.isBlank() ? "" : reason + "\n") + "다시 검색을 시도해주세요").build()).queue();
            return;
        }
        hook.editOriginalEmbeds(MusicEmbeds.error(requester(),
                "스트리밍 서버와 통신에 실패했습니다.",
                "재통신을 시도합니다. (" + retryAttempt + "/" + MAX_LOADING_MUSIC_RETRY_ATTEMPT + ")"
                        + (reason.isBlank() ? "" : "\n" + reason)).build()).queue();
        CompletableFuture.runAsync(this::loadMusic, CompletableFuture.delayedExecutor(1, TimeUnit.SECONDS));
    }

    private void replyNotInVoiceChannel() {
        hook.editOriginalEmbeds(MusicEmbeds.error(requester(),
                "음악을 재생할 수 없습니다.",
                "음악을 재생하려면 음성채팅방에 먼저 참여해주세요.").build()).queue();
    }

    private static String truncate(String text) {
        if (text == null) return "";
        if (text.length() <= SELECT_MENU_TEXT_LIMIT) return text;
        return text.substring(0, SELECT_MENU_TEXT_LIMIT - 3) + "...";
    }
}
