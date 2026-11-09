package com.example.myapplication.motion;

import java.util.ArrayList;
import java.util.List;

/**
 * 動作展開器：把「一個 token」還原成播放用的關鍵幀。
 *
 * <p>用途：機器人端不需要理解 CIRCLE 的數學，只要在播放時把 token 展開成
 * 少量馬達關鍵幀（每個週期 8 點），就能畫出圓。
 *
 * <p>為什麼這樣省：
 * 原始 30fps、10 秒 = 300 幀；壓成 3 個 token 後，只需約 3×8 = 24 個關鍵幀，
 * 指令數降低一個數量級，且動作語意仍然保留。
 */
public final class PrimitiveExpander {

    /** 每個週期的最少展開點數（Nyquist 的實務值：8 點可穩定判斷方向）。 */
    public static final int POINTS_PER_CYCLE = 8;

    private PrimitiveExpander() {
    }

    /**
     * 取得 token 在某時刻的位置。
     *
     * @param token  動作 token
     * @param timeMs 時間
     * @return {x, y}（肩寬單位）；無位置資訊時回傳 {NaN, NaN}
     */
    public static float[] evaluate(MotionToken token, long timeMs) {
        if (token == null) {
            return new float[]{Float.NaN, Float.NaN};
        }
        long clamped = Math.max(token.startMs, Math.min(token.endMs, timeMs));
        if (token.type == MotionTokenType.CIRCLE && token.durationMs() > 0) {
            float ratio = (clamped - token.startMs) / (float) token.durationMs();
            float direction = token.clockwise ? 1f : -1f;
            float angle = token.startAngleRad
                    + direction * (float) (Math.PI * 2.0) * token.revolutions * ratio;
            float x = token.centerX + token.radius * (float) Math.cos(angle);
            float y = token.centerY + token.radius * (float) Math.sin(angle);
            return new float[]{x, y};
        }
        if (token.keyTimesMs == null || token.keyXs == null || token.keyYs == null
                || token.keyTimesMs.length == 0) {
            return new float[]{Float.NaN, Float.NaN};
        }
        int n = token.keyTimesMs.length;
        if (clamped <= token.keyTimesMs[0]) {
            return new float[]{token.keyXs[0], token.keyYs[0]};
        }
        if (clamped >= token.keyTimesMs[n - 1]) {
            return new float[]{token.keyXs[n - 1], token.keyYs[n - 1]};
        }
        for (int i = 1; i < n; i++) {
            if (clamped <= token.keyTimesMs[i]) {
                long span = Math.max(1L, token.keyTimesMs[i] - token.keyTimesMs[i - 1]);
                float ratio = (clamped - token.keyTimesMs[i - 1]) / (float) span;
                float x = token.keyXs[i - 1] + (token.keyXs[i] - token.keyXs[i - 1]) * ratio;
                float y = token.keyYs[i - 1] + (token.keyYs[i] - token.keyYs[i - 1]) * ratio;
                return new float[]{x, y};
            }
        }
        return new float[]{token.keyXs[n - 1], token.keyYs[n - 1]};
    }

    /**
     * 把 token 展開成機器人播放用的關鍵幀。
     *
     * @param token 動作 token
     * @return 關鍵幀列表（時間嚴格遞增）
     */
    public static List<ExpandedPoint> expand(MotionToken token) {
        List<ExpandedPoint> points = new ArrayList<>();
        if (token == null) {
            return points;
        }
        if (token.type == MotionTokenType.CIRCLE) {
            int cycles = Math.max(1, (int) Math.ceil(token.revolutions - 0.05f));
            int count = Math.max(4, cycles * POINTS_PER_CYCLE);
            // 多一點點，讓最後一點落在終點
            if (token.durationMs() > 0) {
                for (int i = 0; i <= count; i++) {
                    long t = token.startMs + Math.round(token.durationMs() * (i / (double) count));
                    float[] p = evaluate(token, t);
                    points.add(new ExpandedPoint(t, p[0], p[1]));
                }
            }
        } else if (token.keyTimesMs != null) {
            for (int i = 0; i < token.keyTimesMs.length; i++) {
                points.add(new ExpandedPoint(token.keyTimesMs[i],
                        token.keyXs[i], token.keyYs[i]));
            }
        }
        return points;
    }

    /**
     * 一個展開後的播放關鍵幀。
     */
    public static final class ExpandedPoint {
        public final long timeMs;
        public final float x;
        public final float y;

        public ExpandedPoint(long timeMs, float x, float y) {
            this.timeMs = timeMs;
            this.x = x;
            this.y = y;
        }
    }
}
