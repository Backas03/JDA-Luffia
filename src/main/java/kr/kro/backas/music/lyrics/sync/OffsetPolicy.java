package kr.kro.backas.music.lyrics.sync;

import org.jetbrains.annotations.Nullable;

public final class OffsetPolicy {

    public static final long TRUST_LYRICS_WITHIN_FINE_MS = 200;
    public static final long TRUST_LYRICS_WITHIN_COARSE_MS = 800;

    private OffsetPolicy() {
    }

    public static long resolve(long coarseOffsetMs, @Nullable OnsetRefiner.Refinement refinement) {
        if (refinement != null) {
            return Math.abs(refinement.offsetMs()) < TRUST_LYRICS_WITHIN_FINE_MS ? 0 : refinement.offsetMs();
        }
        return Math.abs(coarseOffsetMs) < TRUST_LYRICS_WITHIN_COARSE_MS ? 0 : coarseOffsetMs;
    }
}
