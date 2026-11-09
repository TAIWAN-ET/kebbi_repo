package com.example.myapplication.motion;

import java.util.Locale;

/**
 * 一個「動作原子」token：用少量參數代表一段複雜動作。
 *
 * <p>例：右手在 1000ms 內轉一圈 →
 * {@code RIGHT_WRIST | CIRCLE | 0-1000 | r=0.40 | rev=1.00 | T=1000ms | CW}。
 * 機器人不需要收到 30 個逐幀指令，只需要這一個 token（播放時再展開）。
 *
 * <p>資料分成兩類：
 * <ul>
 *   <li>參數型（CIRCLE）：用圓心、半徑、方向、圈數、週期重建。</li>
 *   <li>折線型（RAISE / SWING / HOLD / FREEFORM）：用稀疏關鍵幀重建。</li>
 * </ul>
 */
public final class MotionToken {

    /** 這組動作追蹤的關節名稱，例如 RIGHT_WRIST / LEFT_WRIST / HEAD。 */
    public String joint = "";
    /** 動作類型。 */
    public MotionTokenType type = MotionTokenType.UNKNOWN;
    /** 開始時間（毫秒）。 */
    public long startMs;
    /** 結束時間（毫秒）。 */
    public long endMs;
    /** 辨識信心值（0~1）。 */
    public float confidence;
    /** 這段動作在原始資料中的取樣點數。 */
    public int sampleCount;
    /** 換算後的每週期取樣點數。 */
    public float samplesPerCycle;
    /** 取樣是否達到標準（>= minSamplesPerCycle）。 */
    public boolean samplingAdequate;

    /** 圓心 x（僅 CIRCLE）。 */
    public float centerX;
    /** 圓心 y（僅 CIRCLE）。 */
    public float centerY;
    /** 半徑（僅 CIRCLE，肩寬倍數）。 */
    public float radius;
    /** 起始角度（弧度，僅 CIRCLE）。 */
    public float startAngleRad;
    /** 是否順時針（畫面座標，僅 CIRCLE / 旋轉類）。 */
    public boolean clockwise;
    /** 繞行圈數（僅 CIRCLE，可為小數）。 */
    public float revolutions;
    /** 週期（毫秒；未知為 0）。 */
    public float periodMs;

    /** 折線關鍵幀時間（RAISE / SWING / WAVE / HOLD / FREEFORM）。 */
    public long[] keyTimesMs;
    /** 折線關鍵幀 x。 */
    public float[] keyXs;
    /** 折線關鍵幀 y。 */
    public float[] keyYs;

    public MotionToken() {
    }

    /**
     * 這段 token 的長度。
     *
     * @return 毫秒
     */
    public long durationMs() {
        return Math.max(0L, endMs - startMs);
    }

    /**
     * 是否為停頓。
     *
     * @return HOLD 回傳 true
     */
    public boolean isHold() {
        return type == MotionTokenType.HOLD;
    }

    /**
     * 建立 HOLD token。
     *
     * @param joint    關節名稱
     * @param startMs  開始時間
     * @param endMs    結束時間
     * @param x        停留位置 x
     * @param y        停留位置 y
     * @param sampleCount 原始取樣點數
     * @return HOLD token
     */
    public static MotionToken hold(String joint, long startMs, long endMs,
            float x, float y, int sampleCount) {
        MotionToken token = new MotionToken();
        token.joint = joint;
        token.type = MotionTokenType.HOLD;
        token.startMs = startMs;
        token.endMs = endMs;
        token.confidence = 1f;
        token.sampleCount = sampleCount;
        token.samplesPerCycle = Math.max(1f, sampleCount);
        token.samplingAdequate = true;
        token.keyTimesMs = new long[]{startMs, endMs};
        token.keyXs = new float[]{x, x};
        token.keyYs = new float[]{y, y};
        return token;
    }

    /**
     * 建立圓形 token。
     *
     * @param joint       關節名稱
     * @param startMs     開始時間
     * @param endMs       結束時間
     * @param stats       原始軌跡特徵（提供圓心、半徑、方向、圈數）
     * @param confidence  信心值
     * @param sampleCount 取樣點數
     * @param config      抽幀標準
     * @return CIRCLE token
     */
    public static MotionToken circle(String joint, long startMs, long endMs,
            Trajectory trajectory, float confidence, SamplingConfig config) {
        Trajectory.Stats stats = trajectory.stats();
        MotionToken token = new MotionToken();
        token.joint = joint;
        token.type = MotionTokenType.CIRCLE;
        token.startMs = startMs;
        token.endMs = endMs;
        token.confidence = confidence;
        token.sampleCount = trajectory.size();
        token.samplesPerCycle = stats.samplesPerCycle;
        token.samplingAdequate = config.isSamplingAdequate(stats.samplesPerCycle);
        token.centerX = stats.centerX;
        token.centerY = stats.centerY;
        token.radius = estimateRadius(trajectory, stats);
        token.startAngleRad = (float) Math.atan2(
                trajectory.y(0) - stats.centerY, trajectory.x(0) - stats.centerX);
        token.clockwise = stats.clockwise;
        token.revolutions = Math.max(0f, stats.revolutions);
        token.periodMs = stats.periodMs();
        token.keyTimesMs = new long[]{startMs, endMs};
        token.keyXs = new float[]{trajectory.x(0), trajectory.x(trajectory.size() - 1)};
        token.keyYs = new float[]{trajectory.y(0), trajectory.y(trajectory.size() - 1)};
        return token;
    }

    /**
     * 建立折線型 token（RAISE / LOWER / SWING / WAVE / HOLD / FREEFORM 等）。
     *
     * @param joint       關節名稱
     * @param type        動作類型
     * @param startMs     開始時間
     * @param endMs       結束時間
     * @param simplified  已簡化的軌跡
     * @param confidence  信心值
     * @param sampleCount 原始取樣點數
     * @param periodMs    週期（未知傳 0）
     * @param config      抽幀標準
     * @return 折線型 token
     */
    public static MotionToken polyline(String joint, MotionTokenType type,
            long startMs, long endMs, Trajectory simplified,
            float confidence, int sampleCount, float periodMs, SamplingConfig config) {
        MotionToken token = new MotionToken();
        token.joint = joint;
        token.type = type;
        token.startMs = startMs;
        token.endMs = endMs;
        token.confidence = confidence;
        token.sampleCount = sampleCount;
        token.periodMs = periodMs;
        token.keyTimesMs = new long[simplified.size()];
        token.keyXs = new float[simplified.size()];
        token.keyYs = new float[simplified.size()];
        for (int i = 0; i < simplified.size(); i++) {
            token.keyTimesMs[i] = simplified.timeMs(i);
            token.keyXs[i] = simplified.x(i);
            token.keyYs[i] = simplified.y(i);
        }
        float spc = periodMs > 0f ? sampleCount * periodMs / Math.max(1f, (float) (endMs - startMs)) : sampleCount;
        token.samplesPerCycle = spc;
        token.samplingAdequate = periodMs <= 0f || config.isSamplingAdequate(spc);
        return token;
    }

    /**
     * 以平均半徑估計圓半徑（比最大半徑耐離群值）。
     *
     * @param trajectory 軌跡
     * @param stats      軌跡特徵
     * @return 半徑
     */
    private static float estimateRadius(Trajectory trajectory, Trajectory.Stats stats) {
        float sum = 0f;
        for (int i = 0; i < trajectory.size(); i++) {
            sum += (float) Math.hypot(trajectory.x(i) - stats.centerX,
                    trajectory.y(i) - stats.centerY);
        }
        return sum / Math.max(1, trajectory.size());
    }

    /**
     * 回傳緊湊的單行描述（可直接寫入 MotionScript）。
     *
     * @return 單行 token
     */
    public String toCompact() {
        StringBuilder sb = new StringBuilder();
        sb.append(joint).append('|').append(type.code())
                .append('|').append(startMs).append('-').append(endMs);
        if (type == MotionTokenType.CIRCLE) {
            sb.append(String.format(Locale.US,
                    "|r=%.3f|rev=%.2f|T=%.0f|%s|conf=%.2f|spc=%.1f",
                    radius, revolutions, periodMs, clockwise ? "CW" : "CCW",
                    confidence, samplesPerCycle));
        } else if (keyXs != null) {
            sb.append(String.format(Locale.US,
                    "|pts=%d|conf=%.2f", keyXs.length, confidence));
        }
        if (!samplingAdequate) {
            sb.append("|UNDERSAMPLED");
        }
        return sb.toString();
    }

    /**
     * 回傳 JSON 物件字串（不含外層 key）。
     *
     * @return JSON 字串
     */
    public String toJson() {
        StringBuilder sb = new StringBuilder();
        sb.append('{');
        sb.append("\"joint\":\"").append(escape(joint)).append("\",");
        sb.append("\"type\":\"").append(type.code()).append("\",");
        sb.append("\"startMs\":").append(startMs).append(',');
        sb.append("\"endMs\":").append(endMs).append(',');
        sb.append(String.format(Locale.US, "\"confidence\":%.3f,", confidence));
        sb.append("\"sampleCount\":").append(sampleCount).append(',');
        sb.append(String.format(Locale.US, "\"samplesPerCycle\":%.2f,", samplesPerCycle));
        sb.append("\"samplingAdequate\":").append(samplingAdequate).append(',');
        if (type == MotionTokenType.CIRCLE) {
            sb.append(String.format(Locale.US, "\"centerX\":%.4f,", centerX));
            sb.append(String.format(Locale.US, "\"centerY\":%.4f,", centerY));
            sb.append(String.format(Locale.US, "\"radius\":%.4f,", radius));
            sb.append(String.format(Locale.US, "\"startAngleRad\":%.4f,", startAngleRad));
            sb.append("\"clockwise\":").append(clockwise).append(',');
            sb.append(String.format(Locale.US, "\"revolutions\":%.3f,", revolutions));
            sb.append(String.format(Locale.US, "\"periodMs\":%.1f,", periodMs));
        } else {
            sb.append(String.format(Locale.US, "\"periodMs\":%.1f,", periodMs));
            sb.append("\"keyTimesMs\":[");
            for (int i = 0; keyTimesMs != null && i < keyTimesMs.length; i++) {
                if (i > 0) {
                    sb.append(',');
                }
                sb.append(keyTimesMs[i]);
            }
            sb.append("],\"keyXs\":[");
            for (int i = 0; keyXs != null && i < keyXs.length; i++) {
                if (i > 0) {
                    sb.append(',');
                }
                sb.append(String.format(Locale.US, "%.4f", keyXs[i]));
            }
            sb.append("],\"keyYs\":[");
            for (int i = 0; keyYs != null && i < keyYs.length; i++) {
                if (i > 0) {
                    sb.append(',');
                }
                sb.append(String.format(Locale.US, "%.4f", keyYs[i]));
            }
            sb.append(']');
        }
        sb.append('}');
        return sb.toString();
    }

    private static String escape(String value) {
        return value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
