package kr.kro.backas.music;

import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Message;

public class MusicSearchQueryInfo {
    private final Identifier identifier;
    private final String query;
    private final Member requestedMember;
    private final Message queryMessage;

    public MusicSearchQueryInfo(Identifier identifier, String query, Member requestedMember, Message queryMessage) {
        this.identifier = identifier;
        this.query = query;
        this.requestedMember = requestedMember;
        this.queryMessage = queryMessage;
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

    public Message getQueryMessage() {
        return queryMessage;
    }
}
