package kr.kro.backas.music;


import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;

public class MusicSelection {

    private final Member requestedMember;
    private final SlashCommandInteractionEvent slashCommandInteractionEvent;
    private final AudioTrack selectedTrack;
    private final boolean aiRecommended;
    private final boolean autoplay;

    public MusicSelection(Member requestedMember, SlashCommandInteractionEvent slashCommandInteractionEvent, AudioTrack selectedTrack) {
        this(requestedMember, slashCommandInteractionEvent, selectedTrack, false, false);
    }

    public MusicSelection(Member requestedMember, SlashCommandInteractionEvent slashCommandInteractionEvent, AudioTrack selectedTrack,
                          boolean aiRecommended) {
        this(requestedMember, slashCommandInteractionEvent, selectedTrack, aiRecommended, false);
    }

    private MusicSelection(Member requestedMember, SlashCommandInteractionEvent slashCommandInteractionEvent, AudioTrack selectedTrack,
                           boolean aiRecommended, boolean autoplay) {
        this.requestedMember = requestedMember;
        this.slashCommandInteractionEvent = slashCommandInteractionEvent;
        this.selectedTrack = selectedTrack;
        this.aiRecommended = aiRecommended;
        this.autoplay = autoplay;
    }

    public MusicSelection(MusicSearchQueryInfo queryInfo, AudioTrack selectedTrack) {
        this(queryInfo.getRequestedMember(), queryInfo.getSlashCommandInteractionEvent(), selectedTrack);
    }

    public static MusicSelection autoplay(Member requestedMember, SlashCommandInteractionEvent slashCommandInteractionEvent,
                                          AudioTrack selectedTrack) {
        return new MusicSelection(requestedMember, slashCommandInteractionEvent, selectedTrack, true, true);
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

    public boolean isAutoplay() {
        return autoplay;
    }
}
