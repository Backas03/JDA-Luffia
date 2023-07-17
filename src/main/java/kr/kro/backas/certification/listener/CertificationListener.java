package kr.kro.backas.certification.listener;

import kr.kro.backas.Main;
import net.dv8tion.jda.api.events.guild.member.GuildMemberRemoveEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;

public class CertificationListener extends ListenerAdapter {

    @Override
    public void onGuildMemberRemove(@NotNull GuildMemberRemoveEvent event) {
        try {
            Main.getLuffia()
                    .getCertificationManager()
                    .removeCertification(event.getUser().getIdLong());
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}
