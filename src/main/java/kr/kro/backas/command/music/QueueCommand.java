package kr.kro.backas.command.music;

import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import com.sedmelluq.discord.lavaplayer.track.AudioTrackInfo;
import kr.kro.backas.Main;
import kr.kro.backas.command.api.CommandSource;
import kr.kro.backas.music.TrackScheduler;
import kr.kro.backas.music.TrackUserData;
import kr.kro.backas.util.DurationUtil;
import kr.kro.backas.util.UserUtil;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;

import java.awt.*;
import java.util.LinkedList;

public class QueueCommand implements CommandSource {
    @Override
    public void onTriggered(MessageReceivedEvent event) {
        Message message = event.getMessage();

        AudioTrack track = Main.getLuffia()
                .getMusicPlayerManager()
                .getTrackScheduler()
                .getNowPlaying();
        if (track == null) {
            message.reply("현재 재생 대기 목록이 비어있습니다.").queue();
            return;
        }
        TrackUserData data = track.getUserData(TrackUserData.class);
        AudioTrackInfo info = track.getInfo();
        EmbedBuilder builder = new EmbedBuilder()
                .setColor(Color.decode("#5e71ef"))
                .setAuthor(info.author)
                .setTitle(info.title, info.uri)
                .setThumbnail(Main.getLuffia().getYoutubeService().getThumbnailURL(info.uri))
                .setFooter(UserUtil.getName(data.message().getMember()))
                .addField(
                        "재생 시간",
                        DurationUtil.formatDurationColon((int) (track.getPosition() / 1000)) + " / " + DurationUtil.formatDurationColon((int) (info.length / 1000)),
                        false
                )
                .addField(
                        "반복 모드",
                        Main.getLuffia().getMusicPlayerManager().getTrackScheduler().getRepeatModeName(),
                        false
                );
        LinkedList<AudioTrack> queue = Main.getLuffia()
                .getMusicPlayerManager()
                .getTrackScheduler()
                .getQueue();
        if (queue.size() > 0) {
            builder.addField("", "아래는 대기열 목록입니다", false);
        }
        for (int i=0; i<queue.size(); i++) {
            AudioTrack t = queue.get(i);
            data = t.getUserData(TrackUserData.class);
            builder.addField(
                    (i + 1) + ". " + t.getInfo().title,
                    UserUtil.getName(data.message().getMember()) + " - " + t.getInfo().uri,
                    false
            );
        }
        message.replyEmbeds(builder.build()).queue();
    }

    @Override
    public String getDescription() {
        return null;
    }

    @Override
    public String getUsage() {
        return "!queue - 현재 재생중인 곡과 대기열을 확인합니다.";
    }

    @Override
    public Long[] getAllowedRoleIds() {
        return new Long[0];
    }
}
