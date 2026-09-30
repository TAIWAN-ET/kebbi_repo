package com.example.myapplication.pc;

import com.example.myapplication.io.DanceScriptIO;
import com.example.myapplication.io.PoseIO;
import com.example.myapplication.math.PoseMath;
import com.example.myapplication.model.DanceScript;
import com.example.myapplication.model.Landmark;
import com.example.myapplication.model.MotionSequence;
import com.example.myapplication.model.PoseFrame;
import com.example.myapplication.model.PoseLandmark;
import com.example.myapplication.robot.DanceScriptBuilder;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * PC 端命令列工具：landmark CSV → {@link DanceScript} JSON。
 *
 * <p>用途：接在 {@code extract_pose.py}（Python + MediaPipe）後面，
 * 直接重用 App 內的 PoseIO / PoseAnalyzer / RobotMapper，產生給 Kebbi 播放的腳本。
 *
 * <p>用法：
 * <pre>
 * java DanceScriptGenerator &lt;landmarks.csv&gt; &lt;world_landmarks.csv | -&gt; &lt;out.json&gt; [name] [intervalMs]
 * </pre>
 *
 * <p>不負責什麼：不跑 MediaPipe、不傳送到機器人。
 */
public final class DanceScriptGenerator {

    /** 與 VideoAnalyzer.SMOOTHING_ALPHA 相同，PC 與 App 分析結果才一致。 */
    private static final float SMOOTHING_ALPHA = 0.5f;

    private static final long DEFAULT_INTERVAL_MS = 200L;

    private DanceScriptGenerator() {
    }

    public static void main(String[] args) throws IOException {
        if (args.length < 3) {
            System.err.println("usage: DanceScriptGenerator <landmarks.csv> <world_landmarks.csv | -> "
                    + "<out.json> [name] [intervalMs]");
            System.exit(2);
        }
        File landmarkCsv = new File(args[0]);
        File worldCsv = "-".equals(args[1]) ? null : new File(args[1]);
        File outJson = new File(args[2]);
        String name = args.length > 3 ? args[3] : stripExtension(landmarkCsv.getName());
        long intervalMs = args.length > 4 ? Long.parseLong(args[4]) : DEFAULT_INTERVAL_MS;

        // Step1：讀 normalized landmark（沿用 App 的 PoseIO）
        MotionSequence sequence = PoseIO.readCsv(landmarkCsv);
        if (sequence.frames.isEmpty()) {
            System.err.println("no frames in " + landmarkCsv);
            System.exit(1);
        }

        // Step2：依時間戳併入 world landmark（角度計算用公制座標）
        int worldMatched = 0;
        if (worldCsv != null) {
            worldMatched = attachWorldLandmarks(sequence.frames, PoseIO.readCsv(worldCsv));
        }

        // Step3：逐幀低通濾波（與 VideoAnalyzer 相同）
        smooth(sequence.frames);

        // Step4：產生腳本
        DanceScript script = new DanceScriptBuilder().build(name, sequence.frames, intervalMs);
        DanceScriptIO.write(outJson, script);

        // Step5：讀回驗證，確認 Kebbi 端用同一份程式碼讀得回來
        DanceScript readBack = DanceScriptIO.read(outJson);
        boolean roundTripOk = readBack.steps.size() == script.steps.size()
                && readBack.getCommandCount() == script.getCommandCount()
                && readBack.durationMs == script.durationMs;

        System.out.println(script.describe());
        System.out.println("world landmarks matched: " + worldMatched + "/" + sequence.frames.size()
                + (worldMatched == 0 ? "  (WARNING: angles fall back to image coords)" : ""));
        System.out.println("round-trip: " + (roundTripOk ? "OK" : "FAILED"));
        System.out.println("wrote " + outJson.getAbsolutePath());
        if (!roundTripOk) {
            System.exit(1);
        }
    }

    /**
     * 把 world landmark 依時間戳對應到 normalized 幀上。
     *
     * @return 成功對應的幀數
     */
    private static int attachWorldLandmarks(List<PoseFrame> frames, MotionSequence world) {
        Map<Long, List<Landmark>> byTime = new HashMap<>();
        for (PoseFrame wf : world.frames) {
            if (wf.landmarks.size() >= PoseLandmark.COUNT) {
                byTime.put(wf.timestampMs, wf.landmarks);
            }
        }
        int matched = 0;
        for (PoseFrame frame : frames) {
            List<Landmark> w = byTime.get(frame.timestampMs);
            if (w != null) {
                frame.worldLandmarks = w;
                matched++;
            }
        }
        return matched;
    }

    /** 對 normalized 與 world 兩組座標各自做一階低通濾波。 */
    private static void smooth(List<PoseFrame> frames) {
        List<Landmark> prevNorm = null;
        List<Landmark> prevWorld = null;
        for (PoseFrame frame : frames) {
            frame.landmarks = smooth(frame.landmarks, prevNorm);
            frame.worldLandmarks = smooth(frame.worldLandmarks, prevWorld);
            prevNorm = frame.landmarks;
            prevWorld = frame.worldLandmarks;
        }
    }

    private static List<Landmark> smooth(List<Landmark> current, List<Landmark> previous) {
        if (current == null || previous == null || previous.size() != current.size()) {
            return current;
        }
        List<Landmark> smoothed = new ArrayList<>(current.size());
        for (int i = 0; i < current.size(); i++) {
            smoothed.add(PoseMath.lowPass(current.get(i), previous.get(i), SMOOTHING_ALPHA));
        }
        return smoothed;
    }

    private static String stripExtension(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot > 0 ? fileName.substring(0, dot) : fileName;
    }
}
