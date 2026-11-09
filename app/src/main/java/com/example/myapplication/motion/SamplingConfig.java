package com.example.myapplication.motion;

/**
 * 動作庫的抽幀與辨識標準參數（單一來源）。
 *
 * <p>用途：把「抽幀標準」變成程式可讀的常數，避免每個地方各自寫 magic number。
 * 所有 MotionLibrary 的辨識、分段、重建驗證都讀這份設定。
 *
 * <p>標準摘要：
 * <ul>
 *   <li>基準抽幀間隔 100ms（10Hz），可捕捉最高 3Hz 的舞蹈動作（Nyquist 3.3 倍餘裕）。</li>
 *   <li>每個週期至少 8 個取樣點；低於 5 點視為欠取樣（UNDERSAMPLED）。</li>
 *   <li>停滯超過 300ms 視為 HOLD，可被壓成單一 token。</li>
 *   <li>相鄰動作間隔小於 150ms 視為同一段，避免碎片化。</li>
 *   <li>重建誤差門檻 8% 肩寬；超過就不簡化，退回 FREEFORM。</li>
 * </ul>
 *
 * <p>不負責什麼：不執行抽幀、不做辨識、不讀寫檔案。
 */
public final class SamplingConfig {

    /** 標準版本。 */
    public static final int SCHEMA_VERSION = 1;

    /** 基準抽幀間隔（毫秒）：10Hz。 */
    public final long baseIntervalMs;
    /** 最寬鬆抽幀間隔（毫秒）：靜止時可降到 3Hz。 */
    public final long maxIntervalMs;
    /** 最密抽幀間隔（毫秒）：快速動作可提升到 30Hz。 */
    public final long minIntervalMs;
    /** 理想上每個動作週期的取樣點數。 */
    public final int idealSamplesPerCycle;
    /** 可接受的最低每週期取樣點數。 */
    public final int minSamplesPerCycle;
    /** 停滯多久算 HOLD（毫秒）。 */
    public final long dwellMs;
    /** 位移死區（肩寬倍數）；小於此值視為抖動。 */
    public final float deadband;
    /** 動作判定速度門檻（肩寬／秒）。 */
    public final float activeSpeed;
    /** 相鄰動作合併容忍間隔（毫秒）。 */
    public final long mergeGapMs;
    /** 一個動作原子的最短長度（毫秒）。 */
    public final long minSegmentMs;
    /** 一個動作原子的最少取樣點數。 */
    public final int minSegmentSamples;
    /** 辨識最低信心值；低於此值退回 FREEFORM。 */
    public final float minConfidence;
    /** 重建誤差上限（肩寬倍數）。 */
    public final float maxReconstructionRmse;
    /** 分段遞迴的最大深度。 */
    public final int maxSplitDepth;

    private SamplingConfig(Builder b) {
        this.baseIntervalMs = b.baseIntervalMs;
        this.maxIntervalMs = b.maxIntervalMs;
        this.minIntervalMs = b.minIntervalMs;
        this.idealSamplesPerCycle = b.idealSamplesPerCycle;
        this.minSamplesPerCycle = b.minSamplesPerCycle;
        this.dwellMs = b.dwellMs;
        this.deadband = b.deadband;
        this.activeSpeed = b.activeSpeed;
        this.mergeGapMs = b.mergeGapMs;
        this.minSegmentMs = b.minSegmentMs;
        this.minSegmentSamples = b.minSegmentSamples;
        this.minConfidence = b.minConfidence;
        this.maxReconstructionRmse = b.maxReconstructionRmse;
        this.maxSplitDepth = b.maxSplitDepth;
    }

    /**
     * 標準設定（Kebbi Dance Motion Library v1）。
     *
     * @return 標準抽幀與辨識參數
     */
    public static SamplingConfig standard() {
        return new Builder().build();
    }

    /**
     * 依偵測到的動作週期，計算建議的抽幀間隔。
     *
     * <p>原理：每個週期至少 {@link #idealSamplesPerCycle} 點，
     * 因此 interval <= period / idealSamplesPerCycle；但不得小於 {@link #minIntervalMs}。
     *
     * @param periodMs 動作週期（毫秒）；未知時傳 0
     * @return 建議抽幀間隔（毫秒）
     */
    public long recommendedIntervalMs(float periodMs) {
        if (periodMs <= 0f) {
            return baseIntervalMs;
        }
        long byCycle = (long) Math.ceil(periodMs / (float) idealSamplesPerCycle);
        long interval = Math.min(baseIntervalMs, byCycle);
        return Math.max(minIntervalMs, interval);
    }

    /**
     * 判斷取樣是否足夠重建一個週期。
     *
     * @param samplesPerCycle 每週期取樣點數
     * @return 若達到最低點數回傳 true
     */
    public boolean isSamplingAdequate(float samplesPerCycle) {
        return samplesPerCycle >= minSamplesPerCycle;
    }

    /**
     * 取樣品質分數（0~1），供辨識信心值使用。
     *
     * @param samplesPerCycle 每週期取樣點數
     * @return 1 表示達到理想點數，0 表示完全不足
     */
    public float samplingQuality(float samplesPerCycle) {
        if (samplesPerCycle <= 0f) {
            return 0f;
        }
        return Math.max(0f, Math.min(1f,
                (samplesPerCycle - minSamplesPerCycle)
                        / Math.max(1f, idealSamplesPerCycle - minSamplesPerCycle)));
    }

    /**
     * 建立自訂設定。
     */
    public static final class Builder {
        private long baseIntervalMs = 100L;
        private long maxIntervalMs = 333L;
        private long minIntervalMs = 33L;
        private int idealSamplesPerCycle = 8;
        private int minSamplesPerCycle = 5;
        private long dwellMs = 300L;
        private float deadband = 0.02f;
        private float activeSpeed = 0.12f;
        private long mergeGapMs = 150L;
        private long minSegmentMs = 200L;
        private int minSegmentSamples = 3;
        private float minConfidence = 0.60f;
        private float maxReconstructionRmse = 0.08f;
        private int maxSplitDepth = 3;

        public Builder baseIntervalMs(long value) {
            this.baseIntervalMs = value;
            return this;
        }

        public Builder maxIntervalMs(long value) {
            this.maxIntervalMs = value;
            return this;
        }

        public Builder minIntervalMs(long value) {
            this.minIntervalMs = value;
            return this;
        }

        public Builder idealSamplesPerCycle(int value) {
            this.idealSamplesPerCycle = value;
            return this;
        }

        public Builder minSamplesPerCycle(int value) {
            this.minSamplesPerCycle = value;
            return this;
        }

        public Builder dwellMs(long value) {
            this.dwellMs = value;
            return this;
        }

        public Builder deadband(float value) {
            this.deadband = value;
            return this;
        }

        public Builder activeSpeed(float value) {
            this.activeSpeed = value;
            return this;
        }

        public Builder mergeGapMs(long value) {
            this.mergeGapMs = value;
            return this;
        }

        public Builder minSegmentMs(long value) {
            this.minSegmentMs = value;
            return this;
        }

        public Builder minSegmentSamples(int value) {
            this.minSegmentSamples = value;
            return this;
        }

        public Builder minConfidence(float value) {
            this.minConfidence = value;
            return this;
        }

        public Builder maxReconstructionRmse(float value) {
            this.maxReconstructionRmse = value;
            return this;
        }

        public Builder maxSplitDepth(int value) {
            this.maxSplitDepth = value;
            return this;
        }

        public SamplingConfig build() {
            return new SamplingConfig(this);
        }
    }
}
