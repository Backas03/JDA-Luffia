package kr.kro.backas.music.lyrics.sync;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

class LeadingQuietTest {

    private static final int RATE = 16_000;

    static byte[] wav(double... secondsAndLevels) {
        int total = 0;
        for (int i = 0; i < secondsAndLevels.length; i += 2) total += (int) (secondsAndLevels[i] * RATE);
        short[] samples = new short[total];
        int at = 0;
        for (int i = 0; i < secondsAndLevels.length; i += 2) {
            int count = (int) (secondsAndLevels[i] * RATE);
            double level = secondsAndLevels[i + 1];
            for (int n = 0; n < count; n++, at++) samples[at] = (short) Math.round(Math.sin(2 * Math.PI * 440 * at / RATE) * level * 32767);
        }
        return PcmCapture.toWav(samples, samples.length, RATE);
    }

    @Test
    void skipsTheQuietSceneBeforeTheSongWithAOneSecondMargin() {
        assertEquals(6_600, LeadingQuiet.skipMs(wav(7.6, 0.02, 30, 0.3)));
    }

    @Test
    void keepsTracksThatStartRightAway() {
        assertEquals(0, LeadingQuiet.skipMs(wav(30, 0.3)));
        assertEquals(0, LeadingQuiet.skipMs(wav(2.5, 0.01, 30, 0.3)));
    }

    @Test
    void aSingleClickInTheIntroDoesNotEndTheQuietPart() {
        assertEquals(6_600, LeadingQuiet.skipMs(wav(3, 0.01, 0.1, 0.5, 4.5, 0.01, 30, 0.3)));
    }

    @Test
    void keepsSoftPartsThatAreStillPartOfTheSong() {
        assertEquals(0, LeadingQuiet.skipMs(wav(8, 0.06, 30, 0.3)));
    }

    @Test
    void leavesAllQuietOrUnreadableAudioAlone() {
        assertEquals(0, LeadingQuiet.skipMs(wav(20, 0)));
        assertEquals(0, LeadingQuiet.skipMs(new byte[10]));
    }

    @Test
    void dropKeepsTheRestAsAValidWav() {
        byte[] original = wav(3, 0.02, 2, 0.3);
        byte[] trimmed = LeadingQuiet.drop(original, 3_000);
        ByteBuffer header = ByteBuffer.wrap(trimmed).order(ByteOrder.LITTLE_ENDIAN);
        assertEquals(RATE, header.getInt(24));
        assertEquals(2 * RATE * 2, header.getInt(40));
        byte[] tail = new byte[2 * RATE * 2];
        System.arraycopy(original, 44 + 3 * RATE * 2, tail, 0, tail.length);
        byte[] kept = new byte[tail.length];
        System.arraycopy(trimmed, 44, kept, 0, kept.length);
        assertArrayEquals(tail, kept);
    }
}
