package kr.kro.backas.music.filter;

public enum KaraokeMode {
    OFF("끄기"),
    ECHO("에코"),
    VOCAL_REMOVE("보컬 제거");

    private final String name;

    KaraokeMode(String name) {
        this.name = name;
    }

    public String getName() {
        return name;
    }

    public boolean isActive() {
        return this != OFF;
    }

    public static KaraokeMode fromName(String name) {
        for (KaraokeMode mode : values()) {
            if (mode.getName().equals(name)) return mode;
        }
        return null;
    }
}
