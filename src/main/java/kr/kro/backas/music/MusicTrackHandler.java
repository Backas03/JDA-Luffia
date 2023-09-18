package kr.kro.backas.music;

import com.sedmelluq.discord.lavaplayer.player.AudioPlayer;
import com.sedmelluq.discord.lavaplayer.player.event.AudioEventAdapter;
import com.sedmelluq.discord.lavaplayer.tools.FriendlyException;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import com.sedmelluq.discord.lavaplayer.track.AudioTrackEndReason;
import com.sedmelluq.discord.lavaplayer.track.AudioTrackInfo;
import kr.kro.backas.music.service.youtube.YoutubeService;
import kr.kro.backas.util.DurationUtil;
import kr.kro.backas.util.MemberUtil;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.JDA;

import java.awt.*;

public class MusicTrackHandler extends AudioEventAdapter {

    private final MusicTrack musicTrack;

    public MusicTrackHandler(MusicTrack musicTrack) {
        this.musicTrack = musicTrack;
    }

    public static EmbedBuilder getPlayMessage(AudioTrack track, JDA musicBot) {
        MusicSelection selection = track.getUserData(MusicSelection.class);
        AudioTrackInfo info = track.getInfo();
        return new EmbedBuilder()
                .setColor(Color.decode("#5e71ef"))
                .setTitle(info.title, YoutubeService.getThumbnailURL(info.uri))
                .setDescription("음악을 재생합니다")
                .addField("노래 봇",
                        MemberUtil.getName(MemberUtil.getMember(musicBot.getSelfUser().getIdLong())),
                        false
                ).setFooter(MemberUtil.getName(selection.getRequestedMember()))
                .addField(
                        "재생 시간",
                        DurationUtil.formatDuration((int) (info.length / 1000)),
                        false
                );
    }

    public static EmbedBuilder getEnqueueMessage(AudioTrack track, JDA musicBot, int position) {
        MusicSelection selection = track.getUserData(MusicSelection.class);
        AudioTrackInfo info = track.getInfo();
        return new EmbedBuilder()
                .setColor(Color.decode("#5e71ef"))
                .setTitle(info.title, YoutubeService.getThumbnailURL(info.uri))
                .setDescription("해당 음악이 대기열 " + position + "번째에 추가되었습니다")
                .addField("노래 봇",
                        MemberUtil.getName(MemberUtil.getMember(musicBot.getSelfUser().getIdLong())),
                        false
                ).setFooter(MemberUtil.getName(selection.getRequestedMember()))
                .addField(
                        "재생 시간",
                        DurationUtil.formatDuration((int) (info.length / 1000)),
                        false
                );
    }

    @Override
    public void onPlayerPause(AudioPlayer player) {

    }

    @Override
    public void onPlayerResume(AudioPlayer player) {
        super.onPlayerResume(player);
    }

    @Override
    public void onTrackEnd(AudioPlayer player, AudioTrack track, AudioTrackEndReason endReason) {
        musicTrack.playNextTrack(track.makeClone());
    }

    @Override
    public void onTrackException(AudioPlayer player, AudioTrack track, FriendlyException exception) {
        super.onTrackException(player, track, exception);
    }

    @Override
    public void onTrackStuck(AudioPlayer player, AudioTrack track, long thresholdMs) {
        super.onTrackStuck(player, track, thresholdMs);
    }

    @Override
    public void onTrackStuck(AudioPlayer player, AudioTrack track, long thresholdMs, StackTraceElement[] stackTrace) {
        super.onTrackStuck(player, track, thresholdMs, stackTrace);
    }


}
