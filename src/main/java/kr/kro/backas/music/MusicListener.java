package kr.kro.backas.music;

import kr.kro.backas.Main;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.channel.concrete.VoiceChannel;
import net.dv8tion.jda.api.entities.channel.middleman.AudioChannel;
import net.dv8tion.jda.api.events.guild.voice.GuildVoiceUpdateEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

public class MusicListener extends ListenerAdapter {
    @Override
    public void onGuildVoiceUpdate(@NotNull GuildVoiceUpdateEvent event) {
        AudioChannel channelLeft = event.getOldValue();
        if (channelLeft == null) {
            return;
        }
        MusicPlayerController controller = Main.getLuffia().getMusicPlayerController();
        controller.expireSearchData(event.getMember()); // 검색 데이터가 존재할 시 메모리 해제

        for (MusicPlayerClient client : controller.getRegisteredClients()) {
            if (!client.hasJoinedToVoiceChannel()) {
                continue;
            }
            VoiceChannel clientVoiceChannel = client.getJoinedVoiceChannel(); // cannot be null
            if (clientVoiceChannel.getIdLong() == channelLeft.getIdLong()) {
                List<Member> members = new ArrayList<>(channelLeft.getMembers());
                members.removeIf(member -> member != null && member.getUser().isBot());
                if (members.isEmpty()) {
                    client.disconnectFromVoiceChannelAndResetTrack();
                }
            }
        }
    }
}
