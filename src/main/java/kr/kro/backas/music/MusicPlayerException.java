package kr.kro.backas.music;

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
