package kr.kro.backas.music.filter;

import com.sedmelluq.discord.lavaplayer.filter.equalizer.Equalizer;

public enum ConfiguredEqualizer {
    NORMAL("일반", new float[Equalizer.BAND_COUNT]),
    BASS_BOOST("저음 강조", new float[] {
            0.20f, 0.25f, 0.25f, 0.20f, 0.15f, 0.10f, 0.05f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f
    }),
    SUPER_BASS_BOOST("초저음 강조", new float[] {
            0.35f, 0.40f, 0.40f, 0.35f, 0.25f, 0.15f, 0.05f, 0f, 0f, 0f, -0.05f, -0.05f, -0.05f, -0.05f, -0.05f
    }),
    TREBLE_BOOST("고음 강조", new float[] {
            0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0.05f, 0.15f, 0.20f, 0.25f, 0.25f, 0.20f
    }),
    SUPER_TREBLE_BOOST("초고음 강조", new float[] {
            -0.05f, -0.05f, -0.05f, 0f, 0f, 0f, 0f, 0f, 0f, 0.10f, 0.25f, 0.35f, 0.40f, 0.40f, 0.35f
    });

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

    public boolean isFlat() {
        for (float gain : bands) {
            if (gain != 0f) return false;
        }
        return true;
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
