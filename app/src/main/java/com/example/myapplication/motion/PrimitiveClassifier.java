package com.example.myapplication.motion;

/**
 * 動作原子分類器：把一段 2D 軌跡判斷成 CIRCLE / SWING / WAVE / RAISE / LOWER / HOLD 等。
 *
 * <p>原理（不依賴 AI，全部是可解釋的幾何量）：
 * <ol>
 *   <li>繞行角 winding：對質心累積角度，判斷轉了幾圈與方向。</li>
 *   <li>閉合度 closure：起點到終點距離 / 路徑長；圓形接近 0。</li>
 *   <li>圓度 circularity：4πA / P²；圓形接近 1，直線接近 0。</li>
 *   <li>方向反轉次數：判斷來回擺盪（揮手、點頭、搖頭）。</li>
 *   <li>線性度 linearity：淨位移 / 路徑長；單向抬起／放下接近 1。</li>
 *   <li>取樣品質：每個週期是否有足夠點數（Nyquist 檢查）。</li>
 * </ol>
 *
 * <p>設計原則：低信心時不硬猜，回傳 FREEFORM，由 builder 用稀疏關鍵幀保留原動作。
 */
public final class PrimitiveClassifier {

    private final SamplingConfig config;

    /**
     * @param config 抽幀與辨識標準
     */
    public PrimitiveClassifier(SamplingConfig config) {
        this.config = config;
    }

    /**
     * 分類一段軌跡。
     *
     * @param joint             關節名稱（RIGHT_WRIST / LEFT_WRIST / HEAD / BODY）
     * @param raw               原始軌跡（單位：肩寬）
     * @param handAboveShoulder 手腕是否高於肩膀（僅手臂類使用，決定 WAVE 或 SWING）
     * @return 分類結果
     */
    public PrimitiveMatch classify(String joint, Trajectory raw, boolean handAboveShoulder) {
        if (raw == null || raw.size() < 2) {
            return new PrimitiveMatch(MotionTokenType.HOLD, 1f, 0f, raw);
        }
        Trajectory t = raw.size() >= 3 ? raw.smoothed(3) : raw;
        Trajectory.Stats s = t.stats();

        // Step1：抖動或停頓
        if (s.pathLength < Math.max(config.deadband, 0.03f) || s.bboxDiag < config.deadband) {
            return new PrimitiveMatch(MotionTokenType.HOLD, 1f, 0f,
                    raw.simplifyRdp(config.deadband));
        }

        float sampleQ = config.samplingQuality(s.samplesPerCycle);
        String family = familyOf(joint);

        // Step2：圓形（繞行足夠、閉合、圓度足夠、取樣足夠）
        if (!"HEAD".equals(family) && !"BODY".equals(family)) {
            boolean enoughSamples = s.samplesPerCycle >= config.minSamplesPerCycle - 0.5f;
            if (s.revolutions >= 0.65f && s.closure <= 0.45f
                    && s.circularity >= 0.45f && enoughSamples) {
                float conf = 0.40f
                        + 0.35f * Math.min(1f, s.circularity)
                        + 0.15f * (1f - Math.min(1f, s.closure))
                        + 0.10f * sampleQ;
                if (s.revolutions < 0.85f) {
                    conf *= 0.85f;
                }
                if (s.samplesPerCycle < config.idealSamplesPerCycle) {
                    conf *= 0.95f;
                }
                return new PrimitiveMatch(MotionTokenType.CIRCLE, clamp01(conf),
                        s.periodMs(), t);
            }
        }

        // Step3：來回擺盪（兩次以上方向反轉）
        if (s.directionReversals >= 2 && s.pathLength > 0.10f) {
            float reversalScore = clamp01(s.directionReversals / 4f);
            float shapeScore = clamp01(1f - Math.min(1f, s.circularity));
            float conf = 0.35f + 0.25f * reversalScore + 0.25f * shapeScore + 0.15f * sampleQ;
            if (!config.isSamplingAdequate(s.samplesPerCycle)) {
                conf *= 0.75f;
            }
            if ("HEAD".equals(family)) {
                boolean vertical = Math.abs(s.bboxHeight) >= Math.abs(s.bboxWidth);
                return new PrimitiveMatch(vertical ? MotionTokenType.NOD : MotionTokenType.SHAKE,
                        clamp01(conf), s.periodMs(), oscillationKeyframes(raw, s.periodMs()));
            }
            if ("BODY".equals(family)) {
                return new PrimitiveMatch(MotionTokenType.FREEFORM, 0.3f,
                        s.periodMs(), raw.simplifyRdp(config.deadband));
            }
            MotionTokenType type = handAboveShoulder
                    ? MotionTokenType.WAVE : MotionTokenType.SWING;
            return new PrimitiveMatch(type, clamp01(conf), s.periodMs(),
                    oscillationKeyframes(raw, s.periodMs()));
        }

        // Step4：單向移動（抬起／放下／轉頭／傾身）
        boolean dxDominant = Math.abs(t.x(t.size() - 1) - t.x(0))
                >= Math.abs(t.y(t.size() - 1) - t.y(0));
        float straightness = s.linearity;
        if (straightness >= 0.55f) {
            float displacement = s.netDisplacement;
            float conf = clamp01(0.50f + 0.35f * straightness + 0.15f * sampleQ);
            if ("HEAD".equals(family)) {
                if (dxDominant && Math.abs(t.x(t.size() - 1) - t.x(0)) > 0.15f) {
                    boolean left = t.x(t.size() - 1) < t.x(0);
                    return new PrimitiveMatch(left ? MotionTokenType.TURN_LEFT
                            : MotionTokenType.TURN_RIGHT, conf, 0f,
                            raw.simplifyRdp(config.deadband));
                }
            } else if ("BODY".equals(family)) {
                if (dxDominant && Math.abs(t.x(t.size() - 1) - t.x(0)) > 0.08f) {
                    boolean left = t.x(t.size() - 1) < t.x(0);
                    return new PrimitiveMatch(left ? MotionTokenType.LEAN_LEFT
                            : MotionTokenType.LEAN_RIGHT, conf, 0f,
                            raw.simplifyRdp(config.deadband));
                }
            } else if (!dxDominant && displacement > 0.05f) {
                float dy = t.y(t.size() - 1) - t.y(0);
                MotionTokenType type = dy < 0 ? MotionTokenType.RAISE : MotionTokenType.LOWER;
                return new PrimitiveMatch(type, conf, 0f, raw.simplifyRdp(config.deadband));
            }
        }

        // Step5：無法辨識 → 保留稀疏關鍵幀
        return new PrimitiveMatch(MotionTokenType.FREEFORM, 0.3f,
                s.periodMs(), raw.simplifyRdp(config.deadband));
    }

    private static String familyOf(String joint) {
        if (joint == null) {
            return "ARM";
        }
        String upper = joint.toUpperCase(java.util.Locale.US);
        if (upper.startsWith("HEAD")) {
            return "HEAD";
        }
        if (upper.startsWith("BODY") || upper.startsWith("TORSO")) {
            return "BODY";
        }
        return "ARM";
    }

    private static float clamp01(float value) {
        return Math.max(0f, Math.min(1f, value));
    }

    /**
     * 擺盪類動作的關鍵幀：每週期取 8 點，保留振幅與相位。
     *
     * <p>不能用 RDP：來回擺盪的軌跡會沿同一條線重疊，RDP 會把回程吃掉。
     *
     * @param trajectory 平滑後軌跡
     * @param periodMs   週期（毫秒）
     * @return 重取樣後的關鍵幀軌跡
     */
    private Trajectory oscillationKeyframes(Trajectory trajectory, float periodMs) {
        if (periodMs <= 0f || trajectory.size() < 3) {
            return trajectory;
        }
        long interval = Math.max(config.minIntervalMs,
                Math.round(periodMs / (double) config.idealSamplesPerCycle));
        return trajectory.resample(interval);
    }
}
