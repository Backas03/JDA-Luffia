package kr.kro.backas.command.music;

import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import com.sedmelluq.discord.lavaplayer.track.AudioTrackInfo;
import kr.kro.backas.Main;
import kr.kro.backas.SharedConstant;
import kr.kro.backas.command.api.CommandSource;
import kr.kro.backas.music.MusicPlayerClient;
import kr.kro.backas.music.MusicPlayerController;
import kr.kro.backas.music.MusicSelection;
import kr.kro.backas.music.service.youtube.YoutubeService;
import kr.kro.backas.util.DurationUtil;
import kr.kro.backas.util.MemberUtil;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.channel.concrete.VoiceChannel;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;

import java.awt.*;
import java.util.List;

public class QueueCommand implements CommandSource {
    @Override
    public void onTriggered(MessageReceivedEvent event) {
        Message message = event.getMessage();
        Member member = event.getMember();
        VoiceChannel voiceChannel = MemberUtil.getJoinedVoiceChannel(member); // 길드에서만 서비스시 member는 null 이 될수 없음
        if (voiceChannel == null) {
            EmbedBuilder builder = new EmbedBuilder()
                    .setColor(Color.decode("#f1554a"))
                    .setAuthor(MemberUtil.getName(member))
                    .setTitle("대기열 정보를 확인할 수 없습니다.")
                    .setDescription("대기열을 확인하려면 음성채팅방에 먼저 참여해주세요.")
                    .setFooter(SharedConstant.RELEASE_VERSION);
            message.replyEmbeds(builder.build()).queue();
            return;
        }
        MusicPlayerController controller = Main.getLuffia().getMusicPlayerController();
        MusicPlayerClient client = controller.findFromVoiceChannel(voiceChannel);
        if (client == null) {
            EmbedBuilder builder = new EmbedBuilder()
                    .setColor(Color.decode("#f1554a"))
                    .setAuthor(MemberUtil.getName(member))
                    .setTitle("해당 채널에서 음악을 재생중인 노래 봇이 없습니다")
                    .setFooter(SharedConstant.RELEASE_VERSION);
            message.replyEmbeds(builder.build()).queue();
            return;
        }
        if (!client.hasJoinedToVoiceChannel() || client.getCurrentPlaying() == null) {
            EmbedBuilder builder = new EmbedBuilder()
                    .setColor(Color.decode("#f1554a"))
                    .setAuthor(MemberUtil.getName(member))
                    .setTitle("현재 재생 대기 목록이 비어있습니다.")
                    .addField("노래 봇", MemberUtil.getName(
                            MemberUtil.getMember(
                                    client.getMusicBot().getSelfUser().getIdLong())
                            ), false
                    ).setFooter(SharedConstant.RELEASE_VERSION);
            message.replyEmbeds(builder.build()).queue();
            return;
        }
        AudioTrack currentPlaying = client.getCurrentPlaying();
        AudioTrackInfo currentPlayingInfo = currentPlaying.getInfo();
        MusicSelection currentMusicSelection = currentPlaying.getUserData(MusicSelection.class);
        EmbedBuilder builder = new EmbedBuilder()
                .setColor(Color.decode("#5e71ef"))
                .setAuthor(currentPlayingInfo.author)
                .setTitle(currentPlayingInfo.title, currentPlayingInfo.uri)
                .setThumbnail(YoutubeService.getThumbnailURL(currentPlayingInfo.uri))
                .setFooter(MemberUtil.getName(currentMusicSelection.getRequestedMember()))
                .addField(
                        "재생 시간",
                        DurationUtil.formatDurationColon(
                                (int) (currentPlaying.getPosition() / 1000))
                                + " / " +
                                DurationUtil.formatDurationColon((int) (currentPlayingInfo.length / 1000)),
                        false
                ).addField(
                        "노래 봇",
                        MemberUtil.getName(MemberUtil.getMember(client.getMusicBot().getSelfUser().getIdLong())),
                        false
                ).addField(
                        "반복 모드",
                        client.getRepeatModeName(),
                        false
                );
        List<AudioTrack> queue = client.getTrackQueue();
        if (!queue.isEmpty()) {
            builder.addField("", "아래는 대기열 목록입니다", false);
            for (int i=0; i<queue.size(); i++) {
                AudioTrack track = queue.get(i);
                MusicSelection selection = track.getUserData(MusicSelection.class);
                builder.addField(
                        (i + 1) + ". " + track.getInfo().title,
                        MemberUtil.getName(selection.getRequestedMember()) + " - " + track.getInfo().uri,
                        false
                );
            }
        }
        message.replyEmbeds(builder.build()).queue();
    }

    @Override
    public String getDescription() {
        return null;
    }

    @Override
    public String getUsage() {
        return "!대기열 - 현재 재생중인 곡과 대기열과 같은 모든 정보를 확인합니다.";
    }

    @Override
    public Long[] getAllowedRoleIds() {
        return new Long[0];
    }
}
