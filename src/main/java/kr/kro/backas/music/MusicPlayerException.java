package kr.kro.backas.music;

import net.dv8tion.jda.api.entities.Message;

public class MusicPlayerException extends Exception {

    private final Type errorType;

    public MusicPlayerException(Type errorType) {
        this.errorType = errorType;
    }

    public Type getErrorType() {
        return errorType;
    }

    public enum Type {
        NOT_IN_VOICE_CHANNEL,
        NO_AVAILABLE_CLIENTS_FOUND
    }
}
