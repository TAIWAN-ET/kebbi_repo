package com.example.myapplication.motion;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;

/**
 * 動作庫演算法的 PC 端驗證程式（純 Java，不需要 Android / MediaPipe）。
 *
 * <p>用法：
 * <pre>
 * javac -d out app/src/main/java/com/example/myapplication/motion/*.java
 * java -cp out com.example.myapplication.motion.MotionSamplingVerify
 * </pre>
 *
 * <p>驗證重點：
 * <ol>
 *   <li>手在 1 秒內轉一圈（5Hz / 10Hz 抽幀）→ 1 個 CIRCLE token。</li>
 *   <li>2Hz 抽幀會發生 aliasing → 不得誤判成 CIRCLE。</li>
 *   <li>順/逆時針方向正確。</li>
 *   <li>擺盪、抬手、複合動作能正確切成多個 token。</li>
 *   <li>重建誤差與壓縮率達標。</li>
 * </ol>
 */
public final class MotionSamplingVerify {

    private static final List<String> FAILURES = new ArrayList<>();

    private MotionSamplingVerify() {
    }

    public static void main(String[] args) {
        System.out.println("============================================================");
        System.out.println("  Kebbi Motion Library v1 - Sampling / Primitive Verify");
        System.out.println("============================================================\n");

        SamplingConfig config = SamplingConfig.standard();
        printSamplingStandard(config);

        testCircle("circle 1s @10Hz", 0.40f, 1000L, 1f, 10, true, 0.008f,
                MotionTokenType.CIRCLE, true, 0.85f);
        testCircle("circle 1s @5Hz (200ms)", 0.40f, 1000L, 1f, 5, true, 0.008f,
                MotionTokenType.CIRCLE, true, 0.80f);
        testCircle("circle 1s @5Hz CCW", 0.40f, 1000L, 1f, 5, false, 0.008f,
                MotionTokenType.CIRCLE, false, 0.80f);
        testCircle("circle 2 rev @10Hz", 0.35f, 1000L, 2f, 10, true, 0.006f,
                MotionTokenType.CIRCLE, true, 0.85f);
        testAliasing();
        testSwing();
        testRaiseAndHold();
        testCompound();
        testStaticJitter();
        testLibraryJson();

        System.out.println("\n============================================================");
        if (FAILURES.isEmpty()) {
            System.out.println("  RESULT: ALL PASS");
            System.out.println("============================================================");
        } else {
            System.out.println("  RESULT: " + FAILURES.size() + " FAILURE(S)");
            for (String failure : FAILURES) {
                System.out.println("  - " + failure);
            }
            System.out.println("============================================================");
            System.exit(1);
        }
    }

    private static void printSamplingStandard(SamplingConfig config) {
        System.out.println("Sampling standard:");
        System.out.printf(Locale.US,
                "  base=%dms idealSPC=%d minSPC=%d dwell=%dms deadband=%.3f activeSpeed=%.2f%n",
                config.baseIntervalMs, config.idealSamplesPerCycle, config.minSamplesPerCycle,
                config.dwellMs, config.deadband, config.activeSpeed);
        for (float period : new float[]{500f, 1000f, 2000f}) {
            System.out.printf(Locale.US, "  period %.0fms -> recommended interval %dms (%.1f samples/cycle)%n",
                    period, config.recommendedIntervalMs(period),
                    period / config.recommendedIntervalMs(period));
        }
        System.out.println();
    }

    private static void testCircle(String name, float radius, long periodMs, float revolutions,
            int hz, boolean clockwise, float noise, MotionTokenType expectedType,
            boolean expectedClockwise, float minConfidence) {
        Trajectory track = circle(radius, periodMs, revolutions, hz, noise, clockwise);
        MotionScript script = build("RIGHT_WRIST", track);
        System.out.println("[" + name + "] " + track.describe());
        printScript(script);

        MotionToken token = firstNonHold(script);
        require(token != null, name + ": 應該產生 token");
        if (token != null) {
            require(token.type == expectedType,
                    name + ": 期望 " + expectedType + "，實際 " + token.type);
            require(token.clockwise == expectedClockwise,
                    name + ": 方向錯誤（期望 " + (expectedClockwise ? "CW" : "CCW")
                            + "，實際 " + (token.clockwise ? "CW" : "CCW") + "）");
            require(token.revolutions >= 0.7f * revolutions
                            && token.revolutions <= 1.3f * revolutions,
                    name + ": 圈數誤差過大 " + token.revolutions + " (期望 " + revolutions + ")");
            require(token.confidence >= minConfidence,
                    name + ": 信心值過低 " + token.confidence);
            require(script.metrics().rmse <= SamplingConfig.standard().maxReconstructionRmse,
                    name + ": RMSE 過高 " + script.metrics().rmse);
            require(script.metrics().commandReduction > 0f,
                    name + ": 指令沒有減少");
        }
        System.out.println();
    }

    private static void testAliasing() {
        String name = "aliasing 2Hz";
        Trajectory track = circle(0.40f, 1000L, 1f, 2, 0f, true);
        MotionScript script = build("RIGHT_WRIST", track);
        System.out.println("[" + name + "] " + track.describe());
        printScript(script);

        MotionToken token = firstNonHold(script);
        boolean hasCircle = false;
        for (MotionToken t : script.tokens()) {
            if (t.type == MotionTokenType.CIRCLE) {
                hasCircle = true;
            }
        }
        require(!hasCircle, name + ": 2Hz 抽幀不應辨識出 CIRCLE（aliasing）");
        require(token == null || token.confidence < 0.6f || token.type == MotionTokenType.FREEFORM,
                name + ": 欠取樣時不應給出高信心動作");
        System.out.println();
    }

    private static void testSwing() {
        String name = "swing 1s @10Hz";
        Trajectory track = swing(0.5f, 1000L, 10, 0.2f);
        MotionScript script = build("RIGHT_WRIST", track);
        System.out.println("[" + name + "] " + track.describe());
        printScript(script);
        MotionToken token = firstNonHold(script);
        require(token != null && (token.type == MotionTokenType.SWING
                        || token.type == MotionTokenType.WAVE),
                name + ": 期望 SWING/WAVE，實際 " + (token == null ? "null" : token.type));
        require(script.metrics().rmse <= SamplingConfig.standard().maxReconstructionRmse,
                name + ": RMSE 過高 " + script.metrics().rmse);
        System.out.println();
    }

    private static void testRaiseAndHold() {
        String name = "raise + hold";
        List<Long> times = new ArrayList<>();
        List<Float> xs = new ArrayList<>();
        List<Float> ys = new ArrayList<>();
        for (long t = 0; t <= 1600; t += 200) {
            times.add(t);
            xs.add(0.40f);
            float y = t <= 600 ? 0.20f - (t / 600f) * 0.60f : -0.40f;
            ys.add(y);
        }
        Trajectory track = Trajectory.of(times, xs, ys);
        MotionScript script = build("RIGHT_WRIST", track);
        System.out.println("[" + name + "] " + track.describe());
        printScript(script);

        boolean hasRaise = false;
        boolean hasHold = false;
        for (MotionToken t : script.tokens()) {
            hasRaise |= t.type == MotionTokenType.RAISE;
            hasHold |= t.type == MotionTokenType.HOLD;
        }
        require(hasRaise, name + ": 缺少 RAISE token");
        require(hasHold, name + ": 缺少 HOLD token");
        require(script.metrics().commandReduction > 0.3f,
                name + ": 指令壓縮率不足 " + script.metrics().commandReduction);
        System.out.println();
    }

    private static void testCompound() {
        String name = "compound circle + raise";
        List<Long> times = new ArrayList<>();
        List<Float> xs = new ArrayList<>();
        List<Float> ys = new ArrayList<>();
        float r = 0.35f;
        for (long t = 0; t <= 1000; t += 100) {
            double angle = Math.PI * 2.0 * (t / 1000.0);
            times.add(t);
            xs.add((float) (r * Math.cos(angle)));
            ys.add((float) (r * Math.sin(angle)));
        }
        for (long t = 1100; t <= 1500; t += 100) {
            float ratio = (t - 1000f) / 500f;
            times.add(t);
            xs.add(r);
            ys.add(-0.55f * ratio);
        }
        Trajectory track = Trajectory.of(times, xs, ys);
        MotionScript script = build("RIGHT_WRIST", track);
        System.out.println("[" + name + "] " + track.describe());
        printScript(script);

        boolean hasCircle = false;
        boolean hasRaise = false;
        for (MotionToken t : script.tokens()) {
            hasCircle |= t.type == MotionTokenType.CIRCLE;
            hasRaise |= t.type == MotionTokenType.RAISE;
        }
        require(hasCircle, name + ": 缺少 CIRCLE token（複合動作被單一 token 吃掉）");
        require(hasRaise, name + ": 缺少 RAISE token（沒有切開複合動作）");
        require(script.metrics().rmse <= 0.08f,
                name + ": 複合動作 RMSE 過高 " + script.metrics().rmse);
        System.out.println();
    }

    private static void testStaticJitter() {
        String name = "static jitter";
        Random random = new Random(7);
        List<Long> times = new ArrayList<>();
        List<Float> xs = new ArrayList<>();
        List<Float> ys = new ArrayList<>();
        float x = 0.1f;
        float y = 0.3f;
        for (int i = 0; i < 21; i++) {
            times.add(i * 100L);
            x += (random.nextFloat() - 0.5f) * 0.008f;
            y += (random.nextFloat() - 0.5f) * 0.008f;
            xs.add(x);
            ys.add(y);
        }
        Trajectory track = Trajectory.of(times, xs, ys);
        MotionScript script = build("RIGHT_WRIST", track);
        System.out.println("[" + name + "] " + track.describe());
        printScript(script);
        for (MotionToken token : script.tokens()) {
            require(token.type == MotionTokenType.HOLD,
                    name + ": 靜止抖動應為 HOLD，實際 " + token.type);
        }
        System.out.println();
    }

    private static void testLibraryJson() {
        String json = MotionLibrary.toJson();
        require(json.contains("kebbi.motion-library/v1"), "動作庫 JSON 缺少 schema");
        require(json.contains("\"token\":\"CIRCLE\""), "動作庫 JSON 缺少 CIRCLE");
        require(json.contains("ARM_CIRCLE"), "動作庫 JSON 缺少 ARM_CIRCLE 執行碼");
        System.out.println("[motion library] schema OK, primitives="
                + MotionLibrary.entries().size());
        System.out.println();
    }

    // ------------------------------------------------------------------
    // 合成資料
    // ------------------------------------------------------------------

    private static Trajectory circle(float radius, long periodMs, float revolutions,
            int hz, float noise, boolean clockwise) {
        long duration = Math.round(periodMs * revolutions);
        long step = Math.max(1L, 1000L / hz);
        List<Long> times = new ArrayList<>();
        List<Float> xs = new ArrayList<>();
        List<Float> ys = new ArrayList<>();
        Random random = new Random(42);
        for (long t = 0; t <= duration; t += step) {
            times.add(t);
            double ratio = t / (double) duration;
            double angle = (clockwise ? 1 : -1) * Math.PI * 2.0 * revolutions * ratio;
            xs.add((float) (radius * Math.cos(angle) + noise * (random.nextFloat() - 0.5f) * 2f));
            ys.add((float) (radius * Math.sin(angle) + noise * (random.nextFloat() - 0.5f) * 2f));
        }
        if (times.get(times.size() - 1) != duration) {
            times.add(duration);
            xs.add(radius);
            ys.add(0f);
        }
        return Trajectory.of(times, xs, ys);
    }

    private static Trajectory swing(float amplitude, long periodMs, int hz, float yLevel) {
        long step = Math.max(1L, 1000L / hz);
        List<Long> times = new ArrayList<>();
        List<Float> xs = new ArrayList<>();
        List<Float> ys = new ArrayList<>();
        for (long t = 0; t <= periodMs; t += step) {
            double angle = Math.PI * 2.0 * (t / (double) periodMs);
            times.add(t);
            xs.add((float) (amplitude * Math.sin(angle)));
            ys.add(yLevel);
        }
        return Trajectory.of(times, xs, ys);
    }

    // ------------------------------------------------------------------
    // 輔助
    // ------------------------------------------------------------------

    private static MotionScript build(String joint, Trajectory track) {
        return new MotionScriptBuilder("synthetic", SamplingConfig.standard())
                .addTrack(joint, track)
                .build();
    }

    private static MotionToken firstNonHold(MotionScript script) {
        for (MotionToken token : script.tokens()) {
            if (token.type != MotionTokenType.HOLD) {
                return token;
            }
        }
        return null;
    }

    private static void printScript(MotionScript script) {
        for (MotionToken token : script.tokens()) {
            System.out.println("  " + token.toCompact());
        }
        System.out.println("  " + script.metrics().describe());
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            FAILURES.add(message);
            System.out.println("  [FAIL] " + message);
        }
    }
}
