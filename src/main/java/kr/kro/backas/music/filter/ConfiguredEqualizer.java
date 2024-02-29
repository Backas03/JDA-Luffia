package kr.kro.backas.music.filter;

import com.sedmelluq.discord.lavaplayer.filter.equalizer.Equalizer;

public enum ConfiguredEqualizer {
    /* -0.25 = muted, 0 = origin, 0.25 = amp x2 */
    NORMAL("일반", new float[] { 0.0f, 0.0f, 0.0f, 0.0f, 0.0f }),
    BASS_BOOST("저음 강조", new float[] {0.f, 0.f, 0.05f, 0.125f, 0.25f}),
    SUPER_BASS_BOOST("초저음 강조", new float[] {-0.07f, -0.02f, 0.05f, 0.2f, 0.3f}),
    TREBLE_BOOST("고음 강조", new float[] {0.3125f, 0.25f, 0.1875f, 0.f, 0.f}),
    SUPER_TREBLE_BOOST("초고음 강조", new float[] {0.5f, 0.375f, 0.1f, -0.1f, -0.125f});

    private final String name;
    private final float[] bands;

    ConfiguredEqualizer(String name, float[] bands) {
        this.name = name;
        this.bands = bands;
    }

    public float[] getBands() {
        return bands;
    }

    public void applyTo(Equalizer equalizer) {
        for (int i = 0; i < bands.length; i++) {
            equalizer.setGain(i, bands[i]);
        }
    }

    public String getName() {
        return name;
    }

    public static ConfiguredEqualizer fromName(String name) {
        for (ConfiguredEqualizer eq : values()) {
            if (eq.getName().equals(name)) {
                return eq;
            }
        }
        return null;
    }
}
