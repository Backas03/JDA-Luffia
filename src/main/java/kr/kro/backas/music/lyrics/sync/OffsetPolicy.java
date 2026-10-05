package kr.kro.backas.music.lyrics.sync;

import kr.kro.backas.config.Config;
import org.jetbrains.annotations.Nullable;

public final class OffsetPolicy {

    public static final long TRUST_LYRICS_WITHIN_FINE_MS = Config.get().whisper().autoSync().trustLyricsWithinFineMs();
    public static final long TRUST_LYRICS_WITHIN_COARSE_MS = Config.get().whisper().autoSync().trustLyricsWithinCoarseMs();

    private OffsetPolicy() {
    }

    public static long resolve(long coarseOffsetMs, @Nullable OnsetRefiner.Refinement refinement) {
        if (refinement != null) {
            return Math.abs(refinement.offsetMs()) < TRUST_LYRICS_WITHIN_FINE_MS ? 0 : refinement.offsetMs();
        }
        return Math.abs(coarseOffsetMs) < TRUST_LYRICS_WITHIN_COARSE_MS ? 0 : coarseOffsetMs;
    }
}
