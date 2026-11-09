package com.example.myapplication.motion;

/**
 * 單一動作原子的辨識結果（分類器輸出）。
 *
 * <p>用途：把 {@link PrimitiveClassifier} 的判斷結果連同參數、信心值、
 * 簡化後折線一起帶給 {@link MotionScriptBuilder}，再由 builder 轉成 {@link MotionToken}。
 */
public final class PrimitiveMatch {

    /** 動作類型。 */
    public final MotionTokenType type;
    /** 信心值（0~1）。 */
    public final float confidence;
    /** 估計週期（毫秒；未知為 0）。 */
    public final float periodMs;
    /** 折線型動作的簡化軌跡（圓形為 null）。 */
    public final Trajectory simplified;
    /** 分類器實際分析（平滑後）的軌跡；CIRCLE 參數由此而來。 */
    public final Trajectory analyzed;

    public PrimitiveMatch(MotionTokenType type, float confidence,
            float periodMs, Trajectory simplified) {
        this.type = type;
        this.confidence = confidence;
        this.periodMs = periodMs;
        this.simplified = simplified;
        this.analyzed = simplified;
    }

    /**
     * 是否達到可接受的信心值。
     *
     * @param config 抽幀標準
     * @return 達標回傳 true
     */
    public boolean accepted(SamplingConfig config) {
        return confidence >= config.minConfidence
                && type != MotionTokenType.UNKNOWN
                && type != MotionTokenType.FREEFORM;
    }

    @Override
    public String toString() {
        return type.code() + String.format(java.util.Locale.US, " conf=%.2f", confidence);
    }
}
