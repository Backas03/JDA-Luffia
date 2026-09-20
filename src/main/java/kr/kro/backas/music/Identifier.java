package kr.kro.backas.music;

public enum Identifier {
    YOUTUBE("ytsearch:", "유튜브"),
    YOUTUBE_MUSIC("ytmsearch:", "유튜브 뮤직"),
    SPOTIFY("spsearch:", "스포티파이"),
    URL("", "링크");

    private final String id;
    private final String displayName;

    Identifier(String id, String displayName) {
        this.id = id;
        this.displayName = displayName;
    }

    public String getId() {
        return id;
    }

    public String getDisplayName() {
        return displayName;
    }

    public boolean isSearch() {
        return this != URL;
    }
}
