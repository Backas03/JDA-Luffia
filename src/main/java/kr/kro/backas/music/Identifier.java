package kr.kro.backas.music;

public enum Identifier {
    YOUTUBE("ytsearch:");

    private final String id;

    Identifier(String id) {
        this.id = id;
    }

    public String getId() {
        return id;
    }
}
