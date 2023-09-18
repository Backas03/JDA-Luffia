package kr.kro.backas.music;

public final class RepeatMode {

    private RepeatMode() { }

    public static final int NO_REPEAT = 0;
    public static final int REPEAT_ALL = 1;
    public static final int REPEAT_CURRENT = 2;

    public static String getName(int mode) {
        if (REPEAT_ALL == mode) return "모든 트랙 반복";
        if (REPEAT_CURRENT == mode) return "현재 노래 반복";
        return "반복 없음";
    }
}