package kr.kro.backas.music;

import com.sedmelluq.discord.lavaplayer.player.AudioLoadResultHandler;
import com.sedmelluq.discord.lavaplayer.tools.FriendlyException;
import com.sedmelluq.discord.lavaplayer.track.AudioPlaylist;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import com.sedmelluq.discord.lavaplayer.track.AudioTrackInfo;
import kr.kro.backas.util.DurationUtil;
import kr.kro.backas.util.MemberUtil;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.interactions.components.selections.StringSelectMenu;

import java.awt.*;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class MusicLoader implements AudioLoadResultHandler {

    public static final String PLAYLIST_STRING_SELECT_MENU_ID = "music:playlist_selection";
    public static final int MAX_LOADING_MUSIC_RETRY_ATTEMPT = 3;

    private final MusicSearchQueryInfo queryInfo;
    private final MusicPlayerClient musicPlayerClient;
    private Map<String, AudioTrack> loadedTracks;
    private int retryAttempt;

    public MusicLoader(MusicPlayerClient musicPlayerClient, MusicSearchQueryInfo queryInfo) {
        this.queryInfo = queryInfo;
        this.musicPlayerClient = musicPlayerClient;
        this.retryAttempt = 0;
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

    // track 이 하나 이므로 바로 enqueueOrPlay
    @Override
    public void trackLoaded(AudioTrack track) {
        musicPlayerClient.enqueue(new MusicSelection(queryInfo, track), musicPlayerClient.getJoinedVoiceChannel());
    }

    @Override
    public void playlistLoaded(AudioPlaylist playlist) {
        List<AudioTrack> tracks = playlist.getTracks();
        StringSelectMenu.Builder builder = StringSelectMenu.create(PLAYLIST_STRING_SELECT_MENU_ID);
        this.loadedTracks = new HashMap<>();
        for (int i=0; i<tracks.size(); i++) {
            AudioTrack track = tracks.get(i);
            AudioTrackInfo info = track.getInfo();
            String selectMenuValue = String.valueOf(i);
            builder.addOption(info.title,
                    selectMenuValue,
                    info.uri + "\n" + DurationUtil.formatDuration((int) (info.length / 1000))
            );
            this.loadedTracks.put(selectMenuValue, track);
        }
        SlashCommandInteractionEvent slashCommandInteractionEvent = queryInfo.getSlashCommandInteractionEvent();
        slashCommandInteractionEvent.reply(
                "다음은 \"" + queryInfo.getQuery() + "\" 에 대한 검색 결과입니다. (" + tracks.size() + "개)\n" +
                        "재생할 곡을 선택해주세요.")
                .addActionRow(builder.build())
                .queue();
    }

    @Override
    public void noMatches() {
        EmbedBuilder builder = new EmbedBuilder()
                .setColor(Color.decode("#f1554a"))
                .setTitle("검색 데이터가 존재하지 않습니다")
                .setDescription(queryInfo.getQuery())
                .setFooter(MemberUtil.getName(queryInfo.getRequestedMember()));
        queryInfo.getSlashCommandInteractionEvent()
                .replyEmbeds(builder.build())
                .queue();
    }

    @Override
    public void loadFailed(FriendlyException exception) {
        Member member = queryInfo.getRequestedMember();
        SlashCommandInteractionEvent slashCommandInteractionEvent = queryInfo.getSlashCommandInteractionEvent();
        if (++retryAttempt == MAX_LOADING_MUSIC_RETRY_ATTEMPT) {
            EmbedBuilder builder = new EmbedBuilder()
                    .setColor(Color.decode("#FF0000"))
                    .setTitle("검색 데이터 로드에 실패했습니다")
                    .setDescription("다음 곡을 재생합니다")
                    .setFooter(MemberUtil.getName(member));
            slashCommandInteractionEvent.replyEmbeds(builder.build()).queue();
            musicPlayerClient.skipNowPlaying();
            return;
        }
        EmbedBuilder builder = new EmbedBuilder()
                .setColor(Color.decode("#f1554a"))
                .setTitle("스트리밍 서버와 통신에 실패했습니다.")
                .addField("재통신을 시도합니다.", "재시도 횟수 " + retryAttempt + "/" + MAX_LOADING_MUSIC_RETRY_ATTEMPT, false)
                .setFooter(MemberUtil.getName(member));
        slashCommandInteractionEvent.replyEmbeds(builder.build()).queue();
        loadMusic();
    }
}
