package kr.kro.backas.command.music;

import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import com.sedmelluq.discord.lavaplayer.track.AudioTrackInfo;
import kr.kro.backas.Luffia;
import kr.kro.backas.Main;
import kr.kro.backas.command.api.CommandSource;
import kr.kro.backas.util.DurationUtil;
import kr.kro.backas.util.UserUtil;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;

import java.awt.*;

public class NowPlayingCommand implements CommandSource {
    @Override
    public void onTriggered(MessageReceivedEvent event) {
        Message message = event.getMessage();
        AudioTrack track = Main.getLuffia()
                .getMusicPlayerManager()
                .getTrackScheduler()
                .getNowPlaying();
        AudioTrackInfo info = track.getInfo();
        Luffia luffia = Main.getLuffia();
        EmbedBuilder builder = new EmbedBuilder()
                .setColor(Color.decode("#5e71ef"))
                .setAuthor(info.author)
                .setTitle(info.title, info.uri)
                .setThumbnail(luffia.getYoutubeService().getThumbnailURL(info.uri))
                .setFooter(UserUtil.getName(event.getMember()))
                .addField(
                        "재생 시간",
                        DurationUtil.formatDurationColon((int) (track.getPosition() / 1000)) + " / " + DurationUtil.formatDurationColon((int) (info.length / 1000)),
                        false
                ).addField(
                        "반복 모드",
                        Main.getLuffia().getMusicPlayerManager().getTrackScheduler().getRepeatModeName(),
                        false
                );
        message.replyEmbeds(builder.build()).queue();
    }

    @Override
    public String getDescription() {
        return null;
    }

    @Override
    public String getUsage() {
        return "!정보 - 현재 재생중인 노래 정보를 확인합니다.";
    }

    @Override
    public Long[] getAllowedRoleIds() {
        return new Long[0];
    }
}
