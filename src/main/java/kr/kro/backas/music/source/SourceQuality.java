package kr.kro.backas.music.source;

import com.sedmelluq.discord.lavaplayer.tools.JsonBrowser;
import dev.lavalink.youtube.track.format.FormatInfo;
import dev.lavalink.youtube.track.format.StreamFormat;
import org.jetbrains.annotations.Nullable;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * 유튜브에서 실제로 받아 재생하는 원본 스트림의 음질. 곡 카드에 "Opus 129kbps · 48kHz" 처럼 보인다.
 * kbps 는 0 이면 모름이다. 영상이 섞인 예비 스트림과 라이브는 소리만의 비트레이트를 알 수 없어 빼고 보인다.
 */
public record SourceQuality(String codec, int kbps, int sampleRate, boolean live, boolean muxed) {

    public String label() {
        List<String> parts = new ArrayList<>();
        boolean showKbps = kbps > 0 && !live && !muxed;
        parts.add(showKbps ? codec + " " + kbps + "kbps" : codec);
        if (sampleRate > 0) parts.add(kilohertz(sampleRate));
        if (live) parts.add("라이브");
        return String.join(" · ", parts);
    }

    static String kilohertz(int sampleRate) {
        return BigDecimal.valueOf(sampleRate, 3).stripTrailingZeros().toPlainString() + "kHz";
    }

    @Nullable
    static SourceQuality of(StreamFormat format, @Nullable Details details) {
        FormatInfo info = format.getInfo();
        if (info == null) return null;
        String codec = switch (info) {
            case WEBM_OPUS -> "Opus";
            case WEBM_VORBIS, WEBM_VIDEO_VORBIS -> "Vorbis";
            case MP4_AAC_LC, MP4_VIDEO_AAC_LC -> "AAC";
        };
        boolean muxed = info == FormatInfo.WEBM_VIDEO_VORBIS || info == FormatInfo.MP4_VIDEO_AAC_LC;
        boolean live = details != null && details.live();
        int sampleRate = details != null && details.sampleRate() > 0 ? details.sampleRate() : 0;
        // Opus 는 코덱 자체가 48kHz 로만 돈다.
        if (sampleRate == 0 && info == FormatInfo.WEBM_OPUS) sampleRate = 48000;
        return new SourceQuality(codec, kbps(format, details), sampleRate, live, muxed);
    }

    // 유튜브가 주는 bitrate 는 최대치에 가까워 실제보다 높다. 평균 비트레이트, 없으면 파일 크기 ÷ 길이를 쓴다.
    private static int kbps(StreamFormat format, @Nullable Details details) {
        if (details != null && details.averageBitrate() > 0) return (int) Math.round(details.averageBitrate() / 1000.0);
        if (details != null && details.durationMs() > 0 && format.getContentLength() > 0) {
            return (int) Math.round(format.getContentLength() * 8.0 / details.durationMs());
        }
        return 0;
    }

    /** 플레이어 응답의 포맷 항목에서 youtube-source 가 버리는 값들. */
    record Details(long averageBitrate, int sampleRate, long durationMs, boolean live) {

        static Details from(JsonBrowser format, boolean live) {
            return new Details(number(format.get("averageBitrate")), (int) number(format.get("audioSampleRate")),
                    number(format.get("approxDurationMs")), live);
        }

        private static long number(JsonBrowser node) {
            if (node == null || node.isNull()) return 0;
            long value = node.asLong(-1);
            if (value >= 0) return value;
            String text = node.text();
            if (text == null) return 0;
            try {
                return Long.parseLong(text.trim());
            } catch (NumberFormatException e) {
                return 0;
            }
        }
    }
}
