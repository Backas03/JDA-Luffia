package kr.kro.backas.music;

import com.sedmelluq.discord.lavaplayer.player.AudioLoadResultHandler;
import com.sedmelluq.discord.lavaplayer.tools.FriendlyException;
import com.sedmelluq.discord.lavaplayer.track.AudioPlaylist;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import com.sedmelluq.discord.lavaplayer.track.AudioTrackInfo;
import kr.kro.backas.SharedConstant;
import kr.kro.backas.util.DurationUtil;
import kr.kro.backas.util.MemberUtil;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.interactions.InteractionHook;
import net.dv8tion.jda.api.interactions.components.selections.StringSelectMenu;

import java.awt.*;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class MusicLoader implements AudioLoadResultHandler {

    public static final String PLAYLIST_STRING_SELECT_MENU_ID = "music:playlist_selection";
    public static final int MAX_LOADING_MUSIC_RETRY_ATTEMPT = 3;

    private final MusicSearchQueryInfo queryInfo;
    private final MusicPlayerClient musicPlayerClient;
    private Map<String, AudioTrack> loadedTracks;
    private Message repliedMessage;
    private int retryAttempt;
    private InteractionHook deferReplyInteractionHook;

    public MusicLoader(MusicPlayerClient musicPlayerClient, MusicSearchQueryInfo queryInfo) {
        this.queryInfo = queryInfo;
        this.musicPlayerClient = musicPlayerClient;
        this.retryAttempt = 0;
        this.deferReplyInteractionHook = queryInfo.getSlashCommandInteractionEvent()
                .deferReply().complete();
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
            String infoTitle = info.title;
            if (infoTitle.length() > 100) {
                infoTitle = infoTitle.substring(0, 95) + "...";
            }
            String selectMenuValue = String.valueOf(i);
            builder.addOption(infoTitle,
                    selectMenuValue,
                    info.uri + "\n" + DurationUtil.formatDuration((int) (info.length / 1000))
            );
            this.loadedTracks.put(selectMenuValue, track);
        }
        if (repliedMessage == null) {
            // event reply 안됨
            deferReplyInteractionHook.editOriginal(
                            "다음은 ``" + queryInfo.getQuery() + "`` 에 대한 검색 결과입니다. (" + tracks.size() + "개)\n" +
                                    "재생할 곡을 선택해주세요.")
                    .setActionRow(builder.build())
                    .queue();
            return;
        }
        // event 가 reply 되어있으면 통신오류로 리퀘스트 다시 넣고 재검색한 상태
        repliedMessage.editMessage(
                "다음은 ``" + queryInfo.getQuery() + "`` 에 대한 검색 결과입니다. (" + tracks.size() + "개)\n" +
                        "재생할 곡을 선택해주세요.")
                .setActionRow(builder.build())
                .queue();
        repliedMessage.editMessageEmbeds(new EmbedBuilder()
                .setTitle("재통신 성공")
                .setDescription("스트리밍 서버와 " + retryAttempt + "/" + MAX_LOADING_MUSIC_RETRY_ATTEMPT + " 회 만에 통신에 성공하였습니다\n재생할 곡을 선택해주세요.")
                .setAuthor(MemberUtil.getName(queryInfo.getRequestedMember()))
                .setColor(Color.GREEN)
                .setDescription(SharedConstant.RELEASE_VERSION)
                .setTimestamp(Instant.now())
                .build()
        ).queue();
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
                    .setDescription("다시 검색을 시도해주세요")
                    .setFooter(MemberUtil.getName(member));
            slashCommandInteractionEvent.getChannel()
                    .sendMessageEmbeds(builder.build())
                    .queue();
            return;
        }
        EmbedBuilder builder = new EmbedBuilder()
                .setColor(Color.decode("#f1554a"))
                .setTitle("스트리밍 서버와 통신에 실패했습니다.")
                .addField("재통신을 시도합니다.", "재시도 횟수 " + retryAttempt + "/" + MAX_LOADING_MUSIC_RETRY_ATTEMPT, false)
                .setFooter(MemberUtil.getName(member));
        if (repliedMessage == null) {
            slashCommandInteractionEvent.replyEmbeds(builder.build())
                    .queue(hook -> hook.retrieveOriginal().queue(message -> {
                        repliedMessage = message;
                        reloadMusicAfter1Second();
                    }));
        }
        else {
            repliedMessage.editMessageEmbeds(builder.build()).queue(message -> {
                repliedMessage = message;
                reloadMusicAfter1Second();
            });
        }
    }

    private void reloadMusicAfter1Second() {
        new Thread(() -> {
            try {
                Thread.sleep(1000);
                loadMusic();
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }).start();
    }
}
