package kr.kro.backas.music.filter;

import com.sedmelluq.discord.lavaplayer.filter.equalizer.Equalizer;

public enum ConfiguredEqualizer {
    NORMAL("일반", new float[Equalizer.BAND_COUNT]),
    BASS_BOOST("저음 강조", new float[] {
            0.21f, 0.21f, 0.22f, 0.15f, 0.06f, 0.01f, -0.01f, -0.02f, 0f, 0f, 0.01f, 0.03f, 0.03f, 0.01f, 0f
    }),
    SUPER_BASS_BOOST("초저음 강조", new float[] {
            0.23f, 0.33f, 0.33f, 0.19f, 0.05f, -0.02f, -0.03f, -0.03f, -0.01f, 0f, 0.01f, 0.03f, 0.03f, 0.01f, 0f
    }),
    TREBLE_BOOST("고음 강조", new float[] {
            0f, 0f, 0f, 0f, 0f, -0.01f, -0.01f, 0f, 0f, 0.01f, 0.05f, 0.11f, 0.17f, 0.18f, 0.17f
    }),
    SUPER_TREBLE_BOOST("초고음 강조", new float[] {
            0f, 0f, 0f, 0f, 0f, -0.01f, -0.02f, -0.01f, -0.02f, 0f, 0.05f, 0.12f, 0.22f, 0.28f, 0.28f
    }),
    RICH("풍성하게", new float[] {
            0.20f, 0.25f, 0.25f, 0.20f, 0.10f, 0.05f, 0f, 0f, 0f, 0.05f, 0.15f, 0.20f, 0.25f, 0.25f, 0.20f
    }),
    VOCAL_BOOST("보컬 강조", new float[] {
            -0.05f, -0.04f, -0.03f, -0.02f, -0.01f, -0.02f, -0.02f, 0f, 0.03f, 0.08f, 0.14f, 0.11f, 0.05f, 0.01f, 0f
    }),
    VOCAL_CUT("보컬 감소", new float[] {
            0.05f, 0.03f, 0.04f, 0.02f, 0.01f, 0f, -0.03f, -0.05f, -0.07f, -0.08f, -0.07f, -0.06f, -0.02f, 0.02f, 0.05f
    }),
    POP("팝", new float[] {
            0.03f, 0.06f, 0.08f, 0.08f, 0.04f, 0f, -0.02f, -0.01f, -0.01f, 0.01f, 0.05f, 0.10f, 0.10f, 0.08f, 0.06f
    }),
    ROCK("록", new float[] {
            0.10f, 0.13f, 0.16f, 0.10f, 0.04f, -0.01f, -0.03f, -0.03f, -0.03f, -0.01f, 0.04f, 0.11f, 0.13f, 0.11f, 0.10f
    }),
    HIPHOP("힙합", new float[] {
            0.18f, 0.22f, 0.22f, 0.12f, 0.04f, 0f, -0.02f, -0.03f, -0.01f, 0.01f, 0.03f, 0.05f, 0.05f, 0.05f, 0.05f
    }),
    ELECTRONIC("일렉트로닉", new float[] {
            0.16f, 0.20f, 0.20f, 0.10f, 0.01f, -0.02f, -0.03f, -0.03f, -0.03f, -0.01f, 0.02f, 0.05f, 0.12f, 0.16f, 0.17f
    }),
    ACOUSTIC("어쿠스틱", new float[] {
            0f, 0.01f, 0.02f, 0.03f, 0.05f, 0.05f, 0.02f, -0.01f, 0f, 0.03f, 0.06f, 0.08f, 0.06f, 0.05f, 0.03f
    }),
    LOUDNESS("라우드니스", new float[] {
            0.17f, 0.17f, 0.15f, 0.10f, 0.04f, 0.01f, -0.01f, 0f, 0f, 0f, 0f, 0.03f, 0.05f, 0.10f, 0.13f
    }),
    KARAOKE("노래방", new float[] {
            0.08f, 0.10f, 0.13f, 0.13f, 0.08f, 0.03f, -0.01f, -0.03f, -0.04f, -0.05f, -0.05f, -0.02f, 0.05f, 0.07f, 0.05f
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
