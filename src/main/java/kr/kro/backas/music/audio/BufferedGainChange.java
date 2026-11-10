package kr.kro.backas.music.audio;

import com.sedmelluq.discord.lavaplayer.track.playback.AudioFrame;
import com.sedmelluq.discord.lavaplayer.track.playback.AudioFrameBuffer;
import com.sedmelluq.discord.lavaplayer.track.playback.ImmutableAudioFrame;
import com.sun.jna.ptr.PointerByReference;
import tomp2p.opuswrapper.Opus;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

final class BufferedGainChange {
    static final int HOLD_FRAMES = 15;
    static final int RAMP_FRAMES = 5;
    static final int PRIME_FRAMES = 4;

    private BufferedGainChange() {
    }

    static int apply(AudioFrameBuffer buffer, MusicOpusFormat format, int complexity, float before, float after) {
        List<AudioFrame> frames = new ArrayList<>();
        buffer.rebuild(frame -> {
            frames.add(frame);
            return frame;
        });
        if (frames.size() <= HOLD_FRAMES) return 0;
        for (AudioFrame frame : frames) {
            if (frame.isTerminator() || !format.equals(frame.getFormat())) return 0;
        }
        float[] pcm = decode(frames, format);
        if (pcm == null) return 0;
        scale(pcm, format, after / before);

        AudioFrame last = frames.getLast();
        MusicOpusEncoder pipeline = EncoderGain.lastEncoder();
        boolean continues = pipeline != null && pipeline.produced(last.getData(), last.getDataLength());
        Map<AudioFrame, AudioFrame> replacements = new IdentityHashMap<>();
        MusicOpusEncoder encoder = new MusicOpusEncoder(format, format.bitrate(), complexity);
        try {
            float[] tail = continues ? pipeline.tail(after) : new float[encoder.lookahead() * format.channelCount];
            encode(frames, pcm, tail, format, encoder, replacements);
            buffer.rebuild(frame -> replacements.getOrDefault(frame, frame));
            if (continues) pipeline.adopt(encoder, after);
            else EncoderGain.requestIndependent();
        } finally {
            encoder.close();
        }
        return replacements.size();
    }

    static float[] decode(List<AudioFrame> frames, MusicOpusFormat format) {
        int frameLength = format.totalSampleCount();
        IntBuffer error = IntBuffer.allocate(1);
        PointerByReference decoder = Opus.INSTANCE.opus_decoder_create(format.sampleRate, format.channelCount, error);
        if (error.get(0) != Opus.OPUS_OK || decoder == null) return null;
        try {
            FloatBuffer decoded = ByteBuffer.allocateDirect(frameLength * Float.BYTES).order(ByteOrder.nativeOrder()).asFloatBuffer();
            float[] pcm = new float[frames.size() * frameLength];
            for (int i = 0; i < frames.size(); i++) {
                AudioFrame frame = frames.get(i);
                int samples = Opus.INSTANCE.opus_decode_float(decoder, frame.getData(), frame.getDataLength(), decoded, format.chunkSampleCount, 0);
                if (samples != format.chunkSampleCount) return null;
                decoded.get(0, pcm, i * frameLength, frameLength);
            }
            return pcm;
        } finally {
            Opus.INSTANCE.opus_decoder_destroy(decoder);
        }
    }

    private static void scale(float[] pcm, MusicOpusFormat format, float ratio) {
        int channels = format.channelCount;
        int start = HOLD_FRAMES * format.chunkSampleCount;
        int ramp = RAMP_FRAMES * format.chunkSampleCount;
        int total = pcm.length / channels;
        for (int sample = start; sample < total; sample++) {
            int into = sample - start;
            float gain = into >= ramp ? ratio : 1f + (ratio - 1f) * into / ramp;
            for (int channel = 0; channel < channels; channel++) {
                int index = sample * channels + channel;
                pcm[index] = Math.max(-1f, Math.min(1f, pcm[index] * gain));
            }
        }
    }

    private static void encode(List<AudioFrame> frames, float[] pcm, float[] tail, MusicOpusFormat format,
                               MusicOpusEncoder encoder, Map<AudioFrame, AudioFrame> replacements) {
        int frameLength = format.totalSampleCount();
        float[] chunk = new float[frameLength];
        for (int i = HOLD_FRAMES - PRIME_FRAMES; i < frames.size(); i++) {
            int from = i * frameLength + tail.length;
            int available = Math.max(0, Math.min(frameLength, pcm.length - from));
            System.arraycopy(pcm, from, chunk, 0, available);
            if (available < frameLength) {
                System.arraycopy(tail, from + available - pcm.length, chunk, available, frameLength - available);
            }
            byte[] data = encoder.encodeFloat(chunk, i == HOLD_FRAMES);
            if (i < HOLD_FRAMES) continue;
            AudioFrame frame = frames.get(i);
            replacements.put(frame, new ImmutableAudioFrame(frame.getTimecode(), data, frame.getVolume(), frame.getFormat()));
        }
    }
}
