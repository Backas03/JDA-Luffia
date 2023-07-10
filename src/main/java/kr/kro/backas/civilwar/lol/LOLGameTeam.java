package kr.kro.backas.civilwar.lol;

import kr.kro.backas.civilwar.api.GameTeam;

import java.util.HashSet;
import java.util.Set;

public class LOLGameTeam implements GameTeam<LOLGameTeamType> {

    private final Set<LOLParticipant> participants;
    private final LOLGameTeamType teamType;
    private final long voiceChannelId;

    public LOLGameTeam(LOLGameTeamType teamType, long voiceChannelId) {
        this.participants = new HashSet<>();
        this.teamType = teamType;
        this.voiceChannelId = voiceChannelId;
    }

    public void addParticipant(LOLParticipant participant) {
        this.participants.add(participant);
    }

    public Set<LOLParticipant> getParticipants() {
        return participants;
    }

    public long getVoiceChannelId() {
        return voiceChannelId;
    }

    @Override
    public String getName() {
        return teamType.getName();
    }

    @Override
    public LOLGameTeamType getTeamType() {
        return teamType;
    }
}
