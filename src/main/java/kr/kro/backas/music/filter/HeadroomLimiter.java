package kr.kro.backas.music.filter;

import com.sedmelluq.discord.lavaplayer.filter.FloatPcmAudioFilter;

import java.util.Arrays;

/**
 * 필터 체인의 헤드룸 처리. lavaplayer 는 볼륨을 필터 체인 뒤에서 16비트로 바꾼 다음에 곱하기 때문에,
 * 이퀄라이저(최대 +10dB 부스트)가 키운 소리는 볼륨이 낮아도 먼저 ±1 에서 잘려 찌그러진다.
 * 체인 맨 앞에서 {@link Attenuator} 로 -12dB 줄여 두고, 체인 끝의 이 리미터가 같은 만큼 되돌리면서
 * 넘치는 봉우리만 1ms 룩어헤드로 미리 눌러 잘림 없이 내보낸다. 넘치지 않는 구간은 원래 레벨 그대로다.
 */
public final class HeadroomLimiter implements FloatPcmAudioFilter {
    public static final float INPUT_GAIN = 0.25f;
    static final float CEILING = 0.98f;
    static final double LOOKAHEAD_SECONDS = 0.001;
    static final double RELEASE_SECONDS = 0.1;

    private final FloatPcmAudioFilter downstream;
    private final int channels;
    private final float makeup;
    private final int lookahead;
    private final float releaseCoefficient;
    private final float[][] delay;
    private final float[] required;
    private final float[] holds;
    private double holdSum;
    private int delayCursor;
    private int buffered;
    private int windowCursor;
    private float envelope;
    private float[][] out;

    public HeadroomLimiter(FloatPcmAudioFilter downstream, int channels, int sampleRate) {
        this(downstream, channels, sampleRate, 1f / INPUT_GAIN);
    }

    HeadroomLimiter(FloatPcmAudioFilter downstream, int channels, int sampleRate, float makeup) {
        this.downstream = downstream;
        this.channels = channels;
        this.makeup = makeup;
        this.lookahead = Math.max(1, (int) Math.round(sampleRate * LOOKAHEAD_SECONDS));
        this.releaseCoefficient = (float) (1 - Math.exp(-1 / (RELEASE_SECONDS * sampleRate)));
        this.delay = new float[channels][lookahead];
        this.required = new float[lookahead + 1];
        this.holds = new float[lookahead + 1];
        this.out = new float[channels][0];
        reset();
    }

    public int latencySamples() {
        return lookahead;
    }

    private void reset() {
        for (float[] line : delay) Arrays.fill(line, 0f);
        Arrays.fill(required, 1f);
        Arrays.fill(holds, 1f);
        holdSum = holds.length;
        delayCursor = 0;
        buffered = 0;
        windowCursor = 0;
        envelope = 1f;
    }

    @Override
    public void process(float[][] input, int offset, int length) throws InterruptedException {
        if (out[0].length < length) out = new float[channels][length];
        int produced = 0;
        for (int i = 0; i < length; i++) {
            boolean primed = buffered == lookahead;
            float peak = 0f;
            for (int c = 0; c < channels; c++) {
                float sample = input[c][offset + i] * makeup;
                if (primed) out[c][produced] = delay[c][delayCursor];
                delay[c][delayCursor] = sample;
                peak = Math.max(peak, Math.abs(sample));
            }
            float gain = nextGain(peak);
            if (primed) {
                for (int c = 0; c < channels; c++) {
                    out[c][produced] = clamp(out[c][produced] * gain);
                }
                produced++;
            } else {
                buffered++;
            }
            if (++delayCursor == lookahead) delayCursor = 0;
        }
        if (produced > 0) downstream.process(out, 0, produced);
    }

    // 지연된 출력 샘플(lookahead 전)이 창 안의 모든 요구 이득보다 작거나 같은 이득을 받도록
    // 최소값 유지 → 같은 길이 이동평균 → 릴리스만 느리게 하는 순서로 이득을 만든다.
    private float nextGain(float peak) {
        required[windowCursor] = peak > CEILING ? CEILING / peak : 1f;
        float hold = 1f;
        for (float value : required) hold = Math.min(hold, value);
        holdSum += hold - holds[windowCursor];
        holds[windowCursor] = hold;
        if (++windowCursor == holds.length) {
            windowCursor = 0;
            holdSum = 0;
            for (float value : holds) holdSum += value;
        }
        float average = (float) (holdSum / holds.length);
        if (average < envelope) {
            envelope = average;
        } else {
            envelope += (average - envelope) * releaseCoefficient;
        }
        return envelope;
    }

    private static float clamp(float value) {
        return Math.max(-CEILING, Math.min(CEILING, value));
    }

    @Override
    public void seekPerformed(long requestedTime, long providedTime) {
        reset();
    }

    @Override
    public void flush() throws InterruptedException {
        float[][] silence = new float[channels][lookahead];
        process(silence, 0, lookahead);
        reset();
    }

    @Override
    public void close() {
    }

    /** 체인 맨 앞에서 신호를 {@link #INPUT_GAIN} 만큼 줄인다. 2의 거듭제곱이라 float 에서 손실이 없다. */
    public static final class Attenuator implements FloatPcmAudioFilter {
        private final FloatPcmAudioFilter downstream;

        public Attenuator(FloatPcmAudioFilter downstream) {
            this.downstream = downstream;
        }

        @Override
        public void process(float[][] input, int offset, int length) throws InterruptedException {
            for (float[] channel : input) {
                for (int i = offset; i < offset + length; i++) channel[i] *= INPUT_GAIN;
            }
            downstream.process(input, offset, length);
        }

        @Override
        public void seekPerformed(long requestedTime, long providedTime) {
        }

        @Override
        public void flush() {
        }

        @Override
        public void close() {
        }
    }
}
