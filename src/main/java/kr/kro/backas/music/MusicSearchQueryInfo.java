package kr.kro.backas.music;

import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.interactions.callbacks.IReplyCallback;

public class MusicSearchQueryInfo {
    private final Identifier identifier;
    private final String query;
    private final Member requestedMember;
    private final IReplyCallback slashEvent;

    public MusicSearchQueryInfo(Identifier identifier, String query, Member requestedMember, IReplyCallback slashEvent) {
        this.identifier = identifier;
        this.query = query;
        this.requestedMember = requestedMember;
        this.slashEvent = slashEvent;
    }

    public Identifier getIdentifier() {
        return identifier;
    }

    public String getQuery() {
        return query;
    }

    public String getQueryWithIdentifier() {
        return identifier.getId() + query;
    }

    public Member getRequestedMember() {
        return requestedMember;
    }

    public IReplyCallback getInteraction() {
        return slashEvent;
    }
}
