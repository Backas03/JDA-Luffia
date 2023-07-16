package kr.kro.backas.music;

import kr.kro.backas.Main;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.channel.middleman.AudioChannel;
import net.dv8tion.jda.api.events.guild.voice.GuildVoiceUpdateEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

public class MusicListener extends ListenerAdapter {

    private static final Logger LOGGER = LoggerFactory.getLogger(MusicListener.class);

    @Override
    public void onGuildVoiceUpdate(@NotNull GuildVoiceUpdateEvent event) {
        AudioChannel channelLeft = event.getOldValue();
        if (channelLeft == null) {
            return;
        }
        if (event.getOldValue() == null) {
            return;
        }
        if (event.getMember().getUser().isBot()) {
            Main.getLuffia()
                    .getMusicPlayerManager()
                    .getTrackScheduler()
                    .onQuit();
            LOGGER.info("track data cleared because the bot has left the voice channel. {voiceChannelId={}}", channelLeft.getIdLong());
            return;
        }
        AudioChannel currentChannel = Main.getLuffia()
                .getPublishedGuild()
                .getAudioManager()
                .getConnectedChannel();
        if (currentChannel == null) {
            return;
        }
        if (channelLeft.getIdLong() != currentChannel.getIdLong()) {
            return;
        }
        List<Member> members = new ArrayList<>(channelLeft.getMembers());
        members.removeIf(member -> member != null && member.getUser().isBot());
        if (members.isEmpty()) {
            Main.getLuffia()
                    .getMusicPlayerManager()
                    .getTrackScheduler()
                    .quit();
            LOGGER.info("음악 재생중 청자가 모두 퇴장하여 연결을 끊습니다. {voiceChannelId={}}", channelLeft.getIdLong());
        }
    }
}
