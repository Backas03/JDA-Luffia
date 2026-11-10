package kr.kro.backas.music.audio;

public final class EncoderGain {
    static final long LINGER_NS = 1_000_000_000L;

    private static final ThreadLocal<Slot> SLOT = ThreadLocal.withInitial(Slot::new);

    private EncoderGain() {
    }

    @FunctionalInterface
    public interface Body {
        void run() throws InterruptedException;
    }

    public static float forVolume(int volume) {
        if (volume == 100) return 1f;
        if (volume <= 0) return 0f;
        if (volume <= 150) return (float) Math.tan(volume * 0.0079f);
        return 2.4621f * volume / 150f;
    }

    public static void during(float gain, Body body) throws InterruptedException {
        Slot slot = SLOT.get();
        float previous = slot.gain;
        slot.gain = gain;
        slot.depth++;
        try {
            body.run();
        } finally {
            slot.depth--;
            slot.gain = previous;
        }
    }

    public static void linger(float gain) {
        Slot slot = SLOT.get();
        slot.lingering = gain;
        slot.lingerUntil = System.nanoTime() + LINGER_NS;
        slot.hasLinger = true;
    }

    public static void release() {
        SLOT.get().hasLinger = false;
    }

    public static void requestIndependent() {
        SLOT.get().independent = true;
    }

    static boolean takeIndependent() {
        Slot slot = SLOT.get();
        boolean requested = slot.independent;
        slot.independent = false;
        return requested;
    }

    static void noteEncoder(MusicOpusEncoder encoder) {
        SLOT.get().encoder = encoder;
    }

    static void forgetEncoder(MusicOpusEncoder encoder) {
        Slot slot = SLOT.get();
        if (slot.encoder == encoder) slot.encoder = null;
    }

    static MusicOpusEncoder lastEncoder() {
        return SLOT.get().encoder;
    }

    static float current() {
        Slot slot = SLOT.get();
        if (slot.depth > 0) return slot.gain;
        if (slot.hasLinger) {
            if (System.nanoTime() - slot.lingerUntil < 0) return slot.lingering;
            slot.hasLinger = false;
        }
        return 1f;
    }

    private static final class Slot {
        private float gain = 1f;
        private int depth;
        private float lingering = 1f;
        private long lingerUntil;
        private boolean hasLinger;
        private boolean independent;
        private MusicOpusEncoder encoder;
    }
}
