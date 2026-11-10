package kr.kro.backas.music.audio;

import com.sedmelluq.discord.lavaplayer.track.playback.AudioFrame;
import com.sedmelluq.discord.lavaplayer.track.playback.ImmutableAudioFrame;

import java.util.ArrayList;
import java.util.List;

final class OpusTestAudio {

    private OpusTestAudio() {
    }

    static float[] decode(MusicOpusFormat format, List<AudioFrame> frames) {
        float[] pcm = BufferedGainChange.decode(frames, format);
        if (pcm == null) throw new IllegalStateException("opus decoding failed");
        return pcm;
    }

    static float[] decodePackets(MusicOpusFormat format, List<byte[]> packets) {
        List<AudioFrame> frames = new ArrayList<>();
        for (int i = 0; i < packets.size(); i++) frames.add(new ImmutableAudioFrame(i * 20L, packets.get(i), 100, format));
        return decode(format, frames);
    }

    static double rms(float[] pcm, int from, int to) {
        double sum = 0;
        for (int i = from; i < to; i++) sum += (double) pcm[i] * pcm[i];
        return Math.sqrt(sum / (to - from));
    }
}
