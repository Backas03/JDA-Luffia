package kr.kro.backas.music;

public enum RepeatMode {

    NO_REPEAT("반복 없음"),
    REPEAT_ALL("모든 트랙 반복"),
    REPEAT_CURRENT("현재 노래 반복");

    private final String name;

    RepeatMode(String name) {
        this.name = name;
    }

    public String getName() {
        return name;
    }
}