package kr.kro.backas.music.audio;

import com.sedmelluq.discord.lavaplayer.format.AudioDataFormat;
import com.sedmelluq.discord.lavaplayer.format.transcoder.AudioChunkEncoder;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.ptr.PointerByReference;
import tomp2p.opuswrapper.Opus;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;
import java.nio.ShortBuffer;
import java.util.Arrays;

/**
 * lavaplayer 기본 인코더는 비트레이트를 정할 수 없어 libopus 자동값(스테레오 약 99kbps)으로 나간다.
 * 이 인코더는 JDA 가 가져오는 opus-java 로 비트레이트와 음악용 설정을 직접 건다.
 */
final class MusicOpusEncoder implements AudioChunkEncoder {
    private final AudioDataFormat format;
    private PointerByReference encoder;
    private final ShortBuffer pcm;
    private final ByteBuffer packet;
    private final float[] scaled;
    private final short[] lastInput;
    private int lastLength = -1;
    private float lastGain = Float.NaN;
    private boolean closed;

    MusicOpusEncoder(AudioDataFormat format, int bitrate, int complexity) {
        this.format = format;
        IntBuffer error = IntBuffer.allocate(1);
        this.encoder = Opus.INSTANCE.opus_encoder_create(format.sampleRate, format.channelCount, Opus.OPUS_APPLICATION_AUDIO, error);
        if (error.get(0) != Opus.OPUS_OK || encoder == null) {
            throw new IllegalStateException("opus 인코더를 만들 수 없습니다: " + Opus.INSTANCE.opus_strerror(error.get(0)));
        }
        try {
            ctl(Opus.OPUS_SET_BITRATE_REQUEST, bitrate);
            ctl(Opus.OPUS_SET_COMPLEXITY_REQUEST, complexity);
            ctl(Opus.OPUS_SET_SIGNAL_REQUEST, Opus.OPUS_SIGNAL_MUSIC);
            ctl(Opus.OPUS_SET_VBR_REQUEST, 1);
            ctl(Opus.OPUS_SET_VBR_CONSTRAINT_REQUEST, 0);
        } catch (RuntimeException e) {
            Opus.INSTANCE.opus_encoder_destroy(encoder);
            throw e;
        }
        this.pcm = ByteBuffer.allocateDirect(format.totalSampleCount() * Short.BYTES).order(ByteOrder.nativeOrder()).asShortBuffer();
        this.packet = ByteBuffer.allocateDirect(format.maximumChunkSize());
        this.scaled = new float[format.totalSampleCount()];
        this.lastInput = new short[format.totalSampleCount()];
    }

    private void ctl(int request, int value) {
        int result = Opus.INSTANCE.opus_encoder_ctl(encoder, request, value);
        if (result != Opus.OPUS_OK) {
            throw new IllegalStateException("opus 인코더 설정 " + request + "=" + value + " 실패: " + Opus.INSTANCE.opus_strerror(result));
        }
    }

    int bitrate() {
        IntByReference value = new IntByReference();
        Opus.INSTANCE.opus_encoder_ctl(encoder, Opus.OPUS_GET_BITRATE_REQUEST, value);
        return value.getValue();
    }

    int lookahead() {
        IntByReference value = new IntByReference();
        Opus.INSTANCE.opus_encoder_ctl(encoder, Opus.OPUS_GET_LOOKAHEAD_REQUEST, value);
        return value.getValue();
    }

    @Override
    public byte[] encode(ShortBuffer buffer) {
        int length = encodeToPacket(buffer);
        byte[] bytes = new byte[length];
        packet.get(0, bytes, 0, length);
        return bytes;
    }

    @Override
    public void encode(ShortBuffer buffer, ByteBuffer out) {
        int length = encodeToPacket(buffer);
        out.clear();
        out.put(packet.slice(0, length));
        out.flip();
    }

    byte[] encodeFloat(float[] samples, boolean independent) {
        int length = independent ? encodeIndependent(samples) : encodeFloatToPacket(samples);
        byte[] bytes = new byte[length];
        packet.get(0, bytes, 0, length);
        return bytes;
    }

    boolean produced(byte[] data, int length) {
        if (closed || lastLength != length) return false;
        for (int i = 0; i < length; i++) {
            if (packet.get(i) != data[i]) return false;
        }
        return true;
    }

    float[] tail(float gain) {
        int count = lookahead() * format.channelCount;
        float[] tail = new float[count];
        float scale = gain / 32768f;
        int from = lastInput.length - count;
        for (int i = 0; i < count; i++) tail[i] = clamp(lastInput[from + i] * scale);
        return tail;
    }

    void adopt(MusicOpusEncoder other, float gain) {
        if (closed || other.closed) throw new IllegalStateException("이미 닫힌 opus 인코더입니다.");
        Opus.INSTANCE.opus_encoder_destroy(encoder);
        encoder = other.encoder;
        other.closed = true;
        lastGain = gain;
        lastLength = -1;
    }

    // JNA 는 다이렉트 버퍼의 position 을 무시하고 시작 주소를 넘기므로 항상 0 부터 채운 내부 버퍼로 옮겨 쓴다.
    private int encodeToPacket(ShortBuffer buffer) {
        if (closed) throw new IllegalStateException("이미 닫힌 opus 인코더입니다.");
        ShortBuffer input = buffer.duplicate();
        int count = Math.min(input.remaining(), lastInput.length);
        input.get(lastInput, 0, count);
        Arrays.fill(lastInput, count, lastInput.length, (short) 0);
        float gain = EncoderGain.current();
        float from = Float.isNaN(lastGain) ? gain : lastGain;
        lastGain = gain;
        EncoderGain.noteEncoder(this);
        boolean independent = EncoderGain.takeIndependent();
        int length;
        if (gain == 1f && from == 1f && !independent) {
            pcm.clear();
            pcm.put(lastInput);
            pcm.flip();
            length = Opus.INSTANCE.opus_encode(encoder, pcm, format.chunkSampleCount, packet, packet.capacity());
            if (length < 0) {
                throw new IllegalStateException("opus 인코딩 실패: " + Opus.INSTANCE.opus_strerror(length));
            }
        } else {
            fade(from, gain);
            length = independent ? encodeIndependent(scaled) : encodeFloatToPacket(scaled);
        }
        lastLength = length;
        return length;
    }

    private void fade(float from, float to) {
        int channels = format.channelCount;
        int samples = format.chunkSampleCount;
        for (int sample = 0; sample < samples; sample++) {
            float scale = (from + (to - from) * (sample + 1) / samples) / 32768f;
            for (int channel = 0; channel < channels; channel++) {
                int index = sample * channels + channel;
                scaled[index] = clamp(lastInput[index] * scale);
            }
        }
    }

    private int encodeIndependent(float[] samples) {
        ctl(Opus.OPUS_SET_PREDICTION_DISABLED_REQUEST, 1);
        try {
            return encodeFloatToPacket(samples);
        } finally {
            Opus.INSTANCE.opus_encoder_ctl(encoder, Opus.OPUS_SET_PREDICTION_DISABLED_REQUEST, 0);
        }
    }

    private int encodeFloatToPacket(float[] samples) {
        if (closed) throw new IllegalStateException("이미 닫힌 opus 인코더입니다.");
        int length = Opus.INSTANCE.opus_encode_float(encoder, samples, format.chunkSampleCount, packet, packet.capacity());
        if (length < 0) {
            throw new IllegalStateException("opus 인코딩 실패: " + Opus.INSTANCE.opus_strerror(length));
        }
        return length;
    }

    private static float clamp(float value) {
        return Math.max(-1f, Math.min(1f, value));
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        EncoderGain.forgetEncoder(this);
        Opus.INSTANCE.opus_encoder_destroy(encoder);
    }
}
