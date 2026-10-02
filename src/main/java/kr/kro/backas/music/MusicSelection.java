package kr.kro.backas.music;


import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;

public class MusicSelection {

    private final Member requestedMember;
    private final SlashCommandInteractionEvent slashCommandInteractionEvent;
    private final AudioTrack selectedTrack;
    private final boolean aiRecommended;

    public MusicSelection(Member requestedMember, SlashCommandInteractionEvent slashCommandInteractionEvent, AudioTrack selectedTrack) {
        this(requestedMember, slashCommandInteractionEvent, selectedTrack, false);
    }

    public MusicSelection(Member requestedMember, SlashCommandInteractionEvent slashCommandInteractionEvent, AudioTrack selectedTrack,
                          boolean aiRecommended) {
        this.requestedMember = requestedMember;
        this.slashCommandInteractionEvent = slashCommandInteractionEvent;
        this.selectedTrack = selectedTrack;
        this.aiRecommended = aiRecommended;
    }

    public MusicSelection(MusicSearchQueryInfo queryInfo, AudioTrack selectedTrack) {
        this(queryInfo.getRequestedMember(), queryInfo.getSlashCommandInteractionEvent(), selectedTrack);
    }

    public Member getRequestedMember() {
        return requestedMember;
    }

    public SlashCommandInteractionEvent getSlashCommandInteractionEvent() {
        return slashCommandInteractionEvent;
    }

    public AudioTrack getSelectedTrack() {
        return selectedTrack;
    }

    public boolean isAiRecommended() {
        return aiRecommended;
    }
}
