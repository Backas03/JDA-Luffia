package kr.kro.backas.music;

import kr.kro.backas.Main;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.channel.unions.AudioChannelUnion;
import net.dv8tion.jda.api.events.guild.voice.GuildVoiceUpdateEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import org.jetbrains.annotations.NotNull;

import java.util.List;

public class MusicListener extends ListenerAdapter {

    @Override
    public void onGuildVoiceUpdate(@NotNull GuildVoiceUpdateEvent event) {
        AudioChannelUnion channelLeft = event.getChannelLeft();
        if (channelLeft == null) {
            return;
        }
        if (event.getMember().getUser().isBot()) {
            Main.getLuffia()
                    .getMusicPlayerManager()
                    .getTrackScheduler()
                    .onQuit();
            return;
        }
        List<Member> members = channelLeft.getMembers();
        members.removeIf(member -> member.getUser().isBot());
        if (members.isEmpty()) {
            Main.getLuffia()
                    .getMusicPlayerManager()
                    .getTrackScheduler()
                    .quit();
        }
    }
}
