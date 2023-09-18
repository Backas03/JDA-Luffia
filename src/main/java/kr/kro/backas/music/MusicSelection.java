package kr.kro.backas.music;


import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Message;

public class MusicSelection {

    private final Member requestedMember;
    private final Message queryMessage;
    private final AudioTrack selectedTrack;

    public MusicSelection(Member requestedMember, Message queryMessage, AudioTrack selectedTrack) {
        this.requestedMember = requestedMember;
        this.queryMessage = queryMessage;
        this.selectedTrack = selectedTrack;
    }

    public MusicSelection(MusicSearchQueryInfo queryInfo, AudioTrack selectedTrack) {
        this(queryInfo.getRequestedMember(), queryInfo.getQueryMessage(), selectedTrack);
    }

    public Member getRequestedMember() {
        return requestedMember;
    }

    public Message getQueryMessage() {
        return queryMessage;
    }

    public AudioTrack getSelectedTrack() {
        return selectedTrack;
    }
}
