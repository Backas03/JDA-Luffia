package kr.kro.backas.music.lyrics.sync;

import kr.kro.backas.music.lyrics.LyricLine;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OnsetRefinerTest {

    private static final int RATE = 16_000;
    private static final List<LyricLine> LINES = List.of(
            new LyricLine(3_000, "first line of the song"),
            new LyricLine(7_500, "second line comes next"),
            new LyricLine(12_800, "third line is here"),
            new LyricLine(16_100, "fourth line follows"),
            new LyricLine(21_400, "fifth line again"),
            new LyricLine(25_900, "sixth line to end"));

    private static byte[] songWithBurstsAt(long trueOffsetMs, long seconds, long seed) {
        Random random = new Random(seed);
        short[] samples = new short[(int) (RATE * seconds)];
        for (int i = 0; i < samples.length; i++) samples[i] = (short) (random.nextGaussian() * 300);
        for (LyricLine line : LINES) {
            int start = (int) ((line.timeMs() - trueOffsetMs) * RATE / 1000);
            for (int i = start; i < start + RATE / 4 && i < samples.length; i++) {
                samples[i] = (short) (Math.sin(i * 2 * Math.PI * 660 / RATE) * 14_000 + random.nextGaussian() * 2_000);
            }
        }
        return PcmCapture.toWav(samples, samples.length, RATE);
    }

    @Test
    void recoversTheExactShiftFromACoarseWhisperGuess() {
        byte[] wav = songWithBurstsAt(-13_700, 45, 1);
        Optional<OnsetRefiner.Refinement> result = OnsetRefiner.refine(wav, LINES, -13_300);
        assertTrue(result.isPresent());
        assertTrue(Math.abs(result.get().offsetMs() + 13_700) <= 40, String.valueOf(result.get().offsetMs()));
        assertEquals(6, result.get().lines());

        Optional<OnsetRefiner.Refinement> other = OnsetRefiner.refine(songWithBurstsAt(2_200, 45, 2), LINES, 2_650);
        assertTrue(other.isPresent());
        assertTrue(Math.abs(other.get().offsetMs() - 2_200) <= 40, String.valueOf(other.get().offsetMs()));
    }

    @Test
    void givesUpWhenTheAudioHasNoClearOnsetsAtTheLyricLines() {
        Random random = new Random(3);
        short[] noise = new short[RATE * 45];
        for (int i = 0; i < noise.length; i++) noise[i] = (short) (random.nextGaussian() * 3_000);
        assertTrue(OnsetRefiner.refine(PcmCapture.toWav(noise, noise.length, RATE), LINES, 0).isEmpty());
        assertTrue(OnsetRefiner.refine(songWithBurstsAt(0, 45, 4), LINES.subList(0, 2), 0).isEmpty());
    }
}
