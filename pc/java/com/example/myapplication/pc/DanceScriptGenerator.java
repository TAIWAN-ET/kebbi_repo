package com.example.myapplication.pc;

import com.example.myapplication.analysis.MotionScriptFactory;
import com.example.myapplication.analysis.PoseAnalyzer;
import com.example.myapplication.io.DanceScriptIO;
import com.example.myapplication.io.FaceIO;
import com.example.myapplication.io.PoseIO;
import com.example.myapplication.math.PoseMath;
import com.example.myapplication.model.DanceScript;
import com.example.myapplication.model.FaceFrame;
import com.example.myapplication.model.Landmark;
import com.example.myapplication.motion.MotionScript;
import com.example.myapplication.model.MotionSequence;
import com.example.myapplication.model.PoseFeature;
import com.example.myapplication.model.PoseFrame;
import com.example.myapplication.model.PoseLandmark;
import com.example.myapplication.robot.DanceScriptBuilder;
import com.example.myapplication.robot.FaceScriptBuilder;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
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
 *      [face_blendshapes.csv | -]
 * </pre>
 *
 * <p>給了 face_blendshapes.csv（{@code extract_face.py} 的輸出）就同時產生換臉事件（{@link FaceScriptBuilder}）。
 *
 * <p>不負責什麼：不跑 MediaPipe、不傳送到機器人。
 */
public final class DanceScriptGenerator {

    /**
     * 平滑係數（越大越相信新值）。
     *
     * <p>App 內即時分析（VideoAnalyzer）只能往前濾一次，用 0.5；那樣的濾波會讓輸出比影片晚
     * 約一個取樣間隔（200ms）。這裡是離線分析、整段影片都已經在手上，所以往前、往後各濾一次：
     * 兩次的延遲互相抵銷（零相位），動作的時間點不會被濾波拖晚。
     * 每一次用 0.7，兩次合起來的平滑程度和原本單次 0.5 差不多。
     */
    private static final float SMOOTHING_ALPHA = 0.7f;

    private static final long DEFAULT_INTERVAL_MS = 200L;

    private DanceScriptGenerator() {
    }

    public static void main(String[] args) throws IOException {
        if (args.length < 3) {
            System.err.println("usage: DanceScriptGenerator <landmarks.csv> <world_landmarks.csv | -> "
                    + "<out.json> [name] [intervalMs] [face_blendshapes.csv | -]");
            System.exit(2);
        }
        File landmarkCsv = new File(args[0]);
        File worldCsv = "-".equals(args[1]) ? null : new File(args[1]);
        File outJson = new File(args[2]);
        String name = args.length > 3 ? args[3] : stripExtension(landmarkCsv.getName());
        long intervalMs = args.length > 4 ? Long.parseLong(args[4]) : DEFAULT_INTERVAL_MS;
        File faceCsv = args.length > 5 && !"-".equals(args[5]) ? new File(args[5]) : null;

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

        // Step5：表情 → 換臉事件（同一份腳本、同一個時間軸）
        int faceFrames = 0;
        int faceDetected = 0;
        if (faceCsv != null) {
            List<FaceFrame> faces = FaceIO.readCsv(faceCsv);
            faceFrames = faces.size();
            for (FaceFrame face : faces) {
                faceDetected += face.detected ? 1 : 0;
            }
            script.faces = new FaceScriptBuilder().build(faces, intervalMs);
        }
        DanceScriptIO.write(outJson, script);

        // Step6：讀回驗證，確認 Kebbi 端用同一份程式碼讀得回來
        DanceScript readBack = DanceScriptIO.read(outJson);
        boolean roundTripOk = readBack.steps.size() == script.steps.size()
                && readBack.getCommandCount() == script.getCommandCount()
                && readBack.durationMs == script.durationMs
                && readBack.faces.size() == script.faces.size();

        System.out.println(script.describe());
        System.out.println("world landmarks matched: " + worldMatched + "/" + sequence.frames.size()
                + (worldMatched == 0 ? "  (WARNING: angles fall back to image coords)" : ""));
        if (faceCsv != null) {
            System.out.println("faces detected: " + faceDetected + "/" + faceFrames
                    + ", expression changes: " + script.faces.size());
            for (DanceScript.FaceEvent face : script.faces) {
                System.out.println(String.format(Locale.US, "  t=%6dms (video %6dms)  %-9s -> %s  score=%.2f",
                        face.timeMs, face.videoMs, face.expression,
                        face.face.isEmpty() ? "(default face)" : face.face, face.score));
            }
        }
        System.out.println("round-trip: " + (roundTripOk ? "OK" : "FAILED"));

        // Step7：動作庫腳本（token：CIRCLE / WAVE / RAISE / NOD …），與舞蹈腳本放同一個資料夾。
        // 只輸出、不影響 dance_script.json；失敗也不讓整個流程失敗。
        writeMotionScript(sequence.frames, outJson);
        System.out.println("wrote " + outJson.getAbsolutePath());
        if (!roundTripOk) {
            System.exit(1);
        }
    }

    /**
     * 用動作庫（{@code motion/}）把同一份姿態壓成 token 腳本，寫到 {@code motion_script.json / .txt}。
     */
    private static void writeMotionScript(List<PoseFrame> frames, File danceJson) {
        try {
            List<PoseFeature> features = new ArrayList<>();
            List<Long> times = new ArrayList<>();
            for (PoseFrame frame : frames) {
                features.add(PoseAnalyzer.analyzeFrame(frame));
                times.add(frame.timestampMs);
            }
            MotionScript motion = MotionScriptFactory.fromFeatures(features, times, "video");
            File dir = danceJson.getAbsoluteFile().getParentFile();
            writeText(new File(dir, "motion_script.json"), motion.toJson());
            writeText(new File(dir, "motion_script.txt"), motion.toCompact());
            MotionScript.Metrics m = motion.metrics();
            System.out.println(String.format(Locale.US,
                    "motion script: tokens=%d semanticReduction=%.0f%% rmse=%.3f %s -> %s",
                    m.tokenCount, m.semanticReduction * 100f, m.rmse, m.pass ? "OK" : "WARN",
                    new File(dir, "motion_script.json").getPath()));
        } catch (RuntimeException | IOException e) {
            System.out.println("motion script: FAILED (" + e + ")");
        }
    }

    private static void writeText(File file, String text) throws IOException {
        try (Writer w = new OutputStreamWriter(new FileOutputStream(file), StandardCharsets.UTF_8)) {
            w.write(text);
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

    /**
     * 對 normalized 與 world 兩組座標各自做一階低通濾波：先由前往後、再由後往前（零相位）。
     *
     * <p>只往前濾一次會讓每個動作都晚出現（機器人整體慢半拍）；往回再濾一次把這個延遲抵銷掉。
     */
    private static void smooth(List<PoseFrame> frames) {
        smoothPass(frames, 0, frames.size(), 1);
        smoothPass(frames, frames.size() - 1, -1, -1);
    }

    private static void smoothPass(List<PoseFrame> frames, int from, int to, int stride) {
        List<Landmark> prevNorm = null;
        List<Landmark> prevWorld = null;
        for (int i = from; i != to; i += stride) {
            PoseFrame frame = frames.get(i);
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
