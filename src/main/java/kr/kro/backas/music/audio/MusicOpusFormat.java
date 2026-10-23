package kr.kro.backas.music.audio;

import com.sedmelluq.discord.lavaplayer.format.AudioDataFormat;
import com.sedmelluq.discord.lavaplayer.format.OpusAudioDataFormat;
import com.sedmelluq.discord.lavaplayer.format.StandardAudioDataFormats;
import com.sedmelluq.discord.lavaplayer.format.transcoder.AudioChunkEncoder;
import com.sedmelluq.discord.lavaplayer.player.AudioConfiguration;
import net.dv8tion.jda.api.audio.AudioNatives;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 디스코드로 보내는 Opus 출력 형식. 볼륨·필터 때문에 다시 인코딩해야 할 때만 {@link MusicOpusEncoder} 로 정한 비트레이트를 쓴다.
 * 볼륨 100 에 필터가 없으면 lavaplayer 가 원본 Opus 패킷을 그대로 넘기는데, 그 판단이 출력 형식의 equals 라서
 * 같은 채널·샘플레이트·프레임 길이의 Opus 형식과는 같다고 답한다.
 */
public final class MusicOpusFormat extends OpusAudioDataFormat {
    private static final Logger LOGGER = LoggerFactory.getLogger(MusicOpusFormat.class);
    public static final int MIN_BITRATE_KBPS = 32;
    public static final int MAX_BITRATE_KBPS = 510;

    private final int bitrate;
    private final AtomicBoolean reported = new AtomicBoolean();
    private volatile boolean fallback;

    private MusicOpusFormat(int bitrate) {
        super(2, 48000, 960);
        this.bitrate = bitrate;
    }

    /** {@code bitrateKbps} 가 0 이하면 lavaplayer 기본 인코더(자동 비트레이트)를 그대로 쓴다. */
    public static AudioDataFormat discord(int bitrateKbps) {
        if (bitrateKbps <= 0) return StandardAudioDataFormats.DISCORD_OPUS;
        int clamped = Math.max(MIN_BITRATE_KBPS, Math.min(MAX_BITRATE_KBPS, bitrateKbps));
        return new MusicOpusFormat(clamped * 1000);
    }

    public int bitrate() {
        return bitrate;
    }

    @Override
    public AudioChunkEncoder createEncoder(AudioConfiguration configuration) {
        if (!fallback) {
            try {
                if (!AudioNatives.ensureOpus()) throw new IllegalStateException("이 플랫폼에서 opus-java 네이티브를 불러오지 못했습니다.");
                MusicOpusEncoder encoder = new MusicOpusEncoder(this, bitrate, configuration.getOpusEncodingQuality());
                if (reported.compareAndSet(false, true)) {
                    LOGGER.info("재인코딩 Opus 비트레이트 {}kbps (음악 모드, 복잡도 {})", encoder.bitrate() / 1000, configuration.getOpusEncodingQuality());
                }
                return encoder;
            } catch (Throwable e) {
                fallback = true;
                LOGGER.warn("고음질 Opus 인코더를 쓸 수 없어 lavaplayer 기본 인코더(약 99kbps)로 재인코딩합니다.", e);
            }
        }
        return super.createEncoder(configuration);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof OpusAudioDataFormat that)) return false;
        return channelCount == that.channelCount && sampleRate == that.sampleRate && chunkSampleCount == that.chunkSampleCount;
    }

    @Override
    public int hashCode() {
        return super.hashCode();
    }
}
