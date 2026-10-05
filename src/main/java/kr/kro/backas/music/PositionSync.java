package kr.kro.backas.music;

public final class PositionSync {

    public static final long TOLERANCE_MS = 300;
    public static final int CHECK_EVERY_FRAMES = 500;

    private PositionSync() {
    }

    public static double correction(double realMs, long positionMs, double speed, long basePositionMs, double baseRealMs) {
        double expected = (positionMs - basePositionMs) * speed;
        double actual = realMs - baseRealMs;
        double drift = expected - actual;
        return Math.abs(drift) > TOLERANCE_MS ? drift : 0;
    }
}
