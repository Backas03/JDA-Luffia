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

/**
 * lavaplayer 기본 인코더는 비트레이트를 정할 수 없어 libopus 자동값(스테레오 약 99kbps)으로 나간다.
 * 이 인코더는 JDA 가 가져오는 opus-java 로 비트레이트와 음악용 설정을 직접 건다.
 */
final class MusicOpusEncoder implements AudioChunkEncoder {
    private final AudioDataFormat format;
    private final PointerByReference encoder;
    private final ShortBuffer pcm;
    private final ByteBuffer packet;
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

    // JNA 는 다이렉트 버퍼의 position 을 무시하고 시작 주소를 넘기므로 항상 0 부터 채운 내부 버퍼로 옮겨 쓴다.
    private int encodeToPacket(ShortBuffer buffer) {
        if (closed) throw new IllegalStateException("이미 닫힌 opus 인코더입니다.");
        pcm.clear();
        ShortBuffer input = buffer.duplicate();
        if (input.remaining() > pcm.capacity()) input.limit(input.position() + pcm.capacity());
        pcm.put(input);
        while (pcm.hasRemaining()) pcm.put((short) 0);
        pcm.flip();
        int length = Opus.INSTANCE.opus_encode(encoder, pcm, format.chunkSampleCount, packet, packet.capacity());
        if (length < 0) {
            throw new IllegalStateException("opus 인코딩 실패: " + Opus.INSTANCE.opus_strerror(length));
        }
        return length;
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        Opus.INSTANCE.opus_encoder_destroy(encoder);
    }
}
