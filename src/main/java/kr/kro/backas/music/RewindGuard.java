package kr.kro.backas.music;

final class RewindGuard {
    static final long MIN_POSITION_MS = 10_000;
    static final long RESTART_WINDOW_MS = 3_000;
    static final long TOLERANCE_MS = 3_000;
    static final int MAX_RESEEKS = 5;
    static final int WAIT_FRAMES = 500;

    record Verdict(boolean play, long seekTo) {
        static final Verdict PLAY = new Verdict(true, -1);
        static final Verdict DROP = new Verdict(false, -1);
    }

    private Object track;
    private boolean enabled;
    private long position;
    private long progressFrom;
    private long seekTarget = -1;
    private long recoverTo = -1;
    private long lastDropped;
    private int waited;
    private int reseeks;

    synchronized void start(Object track, long position, boolean seekable) {
        this.track = track;
        this.enabled = seekable;
        this.position = position;
        this.progressFrom = position;
        this.seekTarget = -1;
        this.recoverTo = -1;
        this.reseeks = 0;
    }

    synchronized void seeked(Object track, long target) {
        if (track != this.track) return;
        position = target;
        progressFrom = target;
        seekTarget = target;
        recoverTo = -1;
        waited = 0;
    }

    synchronized Verdict onFrame(Object track, long timecode) {
        if (!enabled || track != this.track) return Verdict.PLAY;
        if (seekTarget >= 0) {
            if (Math.abs(timecode - seekTarget) <= TOLERANCE_MS || ++waited > WAIT_FRAMES) {
                seekTarget = -1;
                position = timecode;
            }
            return Verdict.PLAY;
        }
        if (recoverTo >= 0) {
            if (timecode >= recoverTo - TOLERANCE_MS || ++waited > WAIT_FRAMES) {
                recoverTo = -1;
                return played(timecode);
            }
            boolean restartedAgain = timecode < lastDropped;
            lastDropped = timecode;
            return restartedAgain ? reseek(timecode) : Verdict.DROP;
        }
        if (position >= MIN_POSITION_MS && timecode < RESTART_WINDOW_MS) {
            recoverTo = position;
            progressFrom = position;
            lastDropped = timecode;
            return reseek(timecode);
        }
        return played(timecode);
    }

    synchronized long positionOf(Object track, long fallback) {
        return enabled && track == this.track && recoverTo >= 0 ? recoverTo : fallback;
    }

    private Verdict played(long timecode) {
        position = timecode;
        if (timecode - progressFrom >= MIN_POSITION_MS) {
            progressFrom = timecode;
            reseeks = 0;
        }
        return Verdict.PLAY;
    }

    private Verdict reseek(long timecode) {
        waited = 0;
        if (reseeks >= MAX_RESEEKS) {
            enabled = false;
            recoverTo = -1;
            position = timecode;
            return Verdict.PLAY;
        }
        reseeks++;
        return new Verdict(false, recoverTo);
    }
}
