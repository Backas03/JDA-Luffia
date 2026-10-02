package kr.kro.backas.music.llm;

import java.util.ArrayList;
import java.util.List;

public final class LlmEndpoint {

    public enum Mode { UNKNOWN, NLLB, LLM }

    private final String baseUrl;
    private final String label;
    private final String preferredModel;
    private final int slots;
    private final boolean fallback;
    private final SpeedModel speed = new SpeedModel();
    final List<LlmLease> leases = new ArrayList<>();
    long lastChangeAt;
    private volatile Mode mode = Mode.UNKNOWN;
    private volatile String modelId = "";
    private volatile String modelName = "";
    private volatile long failedAt;
    private volatile double lastTokensPerSecond;
    private volatile long lastMeasuredAt;

    public LlmEndpoint(String baseUrl, String label, String preferredModel, int slots, boolean fallback) {
        this.baseUrl = baseUrl;
        this.label = label;
        this.preferredModel = preferredModel;
        this.slots = Math.max(1, slots);
        this.fallback = fallback;
    }

    public String baseUrl() {
        return baseUrl;
    }

    public String label() {
        return label;
    }

    public String preferredModel() {
        return preferredModel;
    }

    public int slots() {
        return slots;
    }

    public boolean isFallback() {
        return fallback;
    }

    public SpeedModel speed() {
        return speed;
    }

    public Mode mode() {
        return mode;
    }

    public String modelId() {
        return modelId;
    }

    public String modelName() {
        return modelName;
    }

    public void markDetected(Mode mode, String modelId, String modelName) {
        this.modelId = modelId;
        this.modelName = modelName;
        this.mode = mode;
        this.failedAt = 0;
    }

    public void markFailed(long now) {
        this.failedAt = now;
        this.mode = Mode.UNKNOWN;
    }

    public boolean isAvailable(long now, long retryMs) {
        long failed = failedAt;
        return failed == 0 || now - failed >= retryMs;
    }

    public boolean isFailed() {
        return failedAt != 0;
    }

    void recordLastSpeed(double tokensPerSecond, long now) {
        lastTokensPerSecond = tokensPerSecond;
        lastMeasuredAt = now;
    }

    double lastTokensPerSecond(long now, long freshMs) {
        return now - lastMeasuredAt <= freshMs ? lastTokensPerSecond : -1;
    }
}
