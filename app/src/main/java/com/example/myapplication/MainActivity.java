package com.example.myapplication;

import android.content.Intent;
import android.graphics.Bitmap;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Bundle;
import android.widget.Button;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;

import androidx.activity.EdgeToEdge;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.google.mediapipe.framework.image.BitmapImageBuilder;
import com.google.mediapipe.framework.image.MPImage;
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark;
import com.google.mediapipe.tasks.core.BaseOptions;
import com.google.mediapipe.tasks.vision.core.RunningMode;
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarker;
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarkerResult;
import com.nuwarobotics.lib.action.ApiManager;
import com.nuwarobotics.lib.action.manager.NuwaRobotManager;
import com.nuwarobotics.lib.action.manager.NuwaVoiceManager;

import java.io.File;
import java.io.FileWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Kebbi 看影片學跳舞專題的主 Activity。
 *
 * <p>用途：提供 Android UI 讓使用者進行以下操作：
 * <ul>
 *   <li>分析影片（上半身角度計算 + 逐幀日誌）</li>
 *   <li>分析內建影片（dance_input.mp4）</li>
 *   <li>選擇並播放機器人姿勢動作</li>
 *   <li>測試機器人內建 motion</li>
 *   <li>停止當前動作</li>
 *   <li>TTS 說話</li>
 *   <li>進入 Camera 測試頁面</li>
 * </ul>
 *
 * <p>誰會呼叫它：
 * Android 系統在 App 啟動時建立此 Activity。
 * 使用者透過按鈕觸發各項功能。
 *
 * <p>不負責什麼：
 * 不直接處理 CSV/JSON 序列化（由 {@link PoseIO} 負責），
 * 不計算角度或距離（由 {@link PoseMath} 和 {@link PoseAnalyzer} 負責），
 * 不做 round-trip 驗證（由 {@link PosePipeline} 負責），
 * 不直接控制機器人動作（透過 {@link NuwaRobotManager} SDK 呼叫）。
 */
public class MainActivity extends AppCompatActivity {
    private static final String POSE_MODEL = "pose_landmarker_lite.task";
    private static final long FRAME_INTERVAL_MS = 200L;
    private static final long MIN_DANCE_EVENT_GAP_MS = 600L;
    private static final long MOTION_STEP_MS = 1200L;
    private static final int MOTION_TEST_COUNT = 5;
    // 離線分析用：只解析前 10 秒、只取上半身關鍵點
    private static final long CLIP_ANALYZE_MS = 10000L;
    private static final String BUILTIN_CLIP = "dance_input.mp4";
    // 上半身關鍵點（鼻/雙肩/雙肘/雙腕/雙髖），用於跳舞學習的最少集合
    private static final int[] UPPER_BODY = {0, 11, 12, 13, 14, 15, 16, 23, 24};
    // PoseAnalyzer 結果輸出用的 CSV 檔案名稱
    private static final String POSE_ANALYZE_CSV = "dance_pose_landmarks.csv";

    private NuwaRobotManager robotManager;
    private NuwaVoiceManager voiceManager;
    private ApiManager apiManager;
    private PoseLandmarker poseLandmarker;
    private TextView statusText;
    private ExecutorService executorService;
    private ActivityResultLauncher<Intent> pickVideoLauncher;
    private Button poseAnalyzeButton;
    private List<DanceStep> latestDanceSteps = Collections.emptyList();
    private volatile boolean dancePlaybackStopped = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_main);
        statusText = findViewById(R.id.statusText);

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });

        // Step1：初始化 Nuwa SDK（必須先 ApiManager.init，再 NuwaRobotManager / NuwaVoiceManager）
        apiManager = ApiManager.getInstance().init(this, getPackageName());
        robotManager = NuwaRobotManager.getInstance().init(this);
        voiceManager = NuwaVoiceManager.getInstance().init(this);

        // Step2：建立單執行緒後端用於非同步分析
        executorService = Executors.newSingleThreadExecutor();

        // Step3：設定 MediaPipe Pose Landmarker
        setupPoseLandmarker();

        // Step4：設定影片選擇器
        setupVideoPicker();

        // Step5：綁定 UI 按鈕並註冊點擊事件
        Button speakButton = findViewById(R.id.speakButton);
        Button pickVideoButton = findViewById(R.id.pickVideoButton);
        Button motionButton = findViewById(R.id.motionButton);
        Button testMotionsButton = findViewById(R.id.testMotionsButton);
        Button stopButton = findViewById(R.id.stopButton);
        Button cameraTestButton = findViewById(R.id.cameraTestButton);

        speakButton.setOnClickListener(v -> speak());
        poseAnalyzeButton.setOnClickListener(v -> printPoseAnalyzerResults());
        pickVideoButton.setOnClickListener(v -> analyzeBuiltInClip());
        motionButton.setOnClickListener(v -> showMotionPicker());
        testMotionsButton.setOnClickListener(v -> testBuiltInMotions());
        stopButton.setOnClickListener(v -> stopMotion());
        cameraTestButton.setOnClickListener(v -> startActivity(new Intent(this, CameraTestActivity.class)));
    }

    /**
     * 初始化 MediaPipe Pose Landmarker（VIDEO 模式）。
     */
    private void setupPoseLandmarker() {
        BaseOptions baseOptions = BaseOptions.builder()
                .setModelAssetPath(POSE_MODEL)
                .build();

        PoseLandmarker.PoseLandmarkerOptions options = PoseLandmarker.PoseLandmarkerOptions.builder()
                .setBaseOptions(baseOptions)
                .setRunningMode(RunningMode.VIDEO)
                .setNumPoses(1)
                .setMinPoseDetectionConfidence(0.5f)
                .setMinPosePresenceConfidence(0.5f)
                .setMinTrackingConfidence(0.5f)
                .build();

        poseLandmarker = PoseLandmarker.createFromOptions(this, options);
    }

    /**
     * 設定影片選擇器，用於從檔案系統選擇影片。
     */
    private void setupVideoPicker() {
        pickVideoLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    if (result.getResultCode() != RESULT_OK || result.getData() == null) {
                        statusText.setText("No video selected");
                        return;
                    }

                    Uri videoUri = result.getData().getData();
                    if (videoUri != null) {
                        analyzeDanceVideo(videoUri);
                    }
                });
    }

    /**
     * 打開系統影片選擇器。
     *
     * @param intent ACTION_OPEN_DOCUMENT intent
     */
    private void pickDanceVideo() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("video/*");
        try {
            pickVideoLauncher.launch(intent);
        } catch (Exception e) {
            statusText.setText("No video picker found");
        }
    }

    /**
     * 分析內建影片（需先用 adb push 放到 App 外部檔案目錄的 dance_input.mp4）。
     *
     * <p>只解析前 10 秒、只取上半身關鍵點，逐幀用 PoseAnalyzer 算角度，
     * 最後把每幀摘要與整段統計顯示在 statusText，方便先看「這支影片數據長怎樣」。
     */
    private void analyzeBuiltInClip() {
        File clip = new File(getExternalFilesDir(null), BUILTIN_CLIP);
        if (!clip.exists()) {
            statusText.setText("找不到 " + BUILTIN_CLIP + "\n請先:\nadb push 影片 "
                    + getExternalFilesDir(null).getAbsolutePath() + "/" + BUILTIN_CLIP);
            return;
        }

        statusText.setText("分析影片前 10 秒（上半身）...");
        executorService.execute(() -> {
            MediaMetadataRetriever retriever = new MediaMetadataRetriever();
            int sampledFrames = 0;
            int poseFrames = 0;
            float sumLeftElbow = 0f, sumRightElbow = 0f, sumShoulder = 0f, sumHead = 0f;
            int leftHandUpCount = 0, rightHandUpCount = 0;
            StringBuilder frameLog = new StringBuilder();

            try {
                // Step1：設定影片來源
                retriever.setDataSource(clip.getAbsolutePath());

                // Step2：取得影片長度並限制為前 10 秒
                String durationText = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
                long durationMs = durationText == null ? 0L : Long.parseLong(durationText);
                long limitMs = Math.min(durationMs, CLIP_ANALYZE_MS);

                // Step3：準備 CSV 寫入器
                File csvFile = new File(getExternalFilesDir(null), POSE_ANALYZE_CSV);
                FileWriter landmarkWriter = new FileWriter(csvFile, false);
                landmarkWriter.write("time_ms,pose_index,landmark_index,x,y,z,visibility,presence\n");

                // Step4：逐幀解析影片
                for (long timeMs = 0; timeMs <= limitMs; timeMs += FRAME_INTERVAL_MS) {
                    Bitmap frame = retriever.getFrameAtTime(timeMs * 1000L, MediaMetadataRetriever.OPTION_CLOSEST);
                    if (frame == null) {
                        continue;
                    }
                    sampledFrames++;

                    // Step5：將 Bitmap 轉為 MediaPipe MPImage
                    MPImage image = new BitmapImageBuilder(frame).build();

                    // Step6：用 PoseLandmarker 偵測姿態
                    PoseLandmarkerResult result = poseLandmarker.detectForVideo(image, timeMs);
                    if (!result.landmarks().isEmpty()) {
                        poseFrames++;

                        // Step7：轉換為 PoseFrame 並計算特徵
                        List<NormalizedLandmark> raw = result.landmarks().get(0);
                        PoseFrame pf = toPoseFrame(timeMs, raw);
                        PoseFeature f = PoseAnalyzer.analyzeFrame(pf);

                        // Step8：寫入 CSV
                        writeLandmarks(landmarkWriter, timeMs, result.landmarks());

                        // Step9：累計角度統計
                        sumLeftElbow += f.leftElbowAngle;
                        sumRightElbow += f.rightElbowAngle;
                        sumShoulder += f.shoulderSlope;
                        sumHead += f.headYaw;
                        if (f.leftWristAboveShoulder) leftHandUpCount++;
                        if (f.rightWristAboveShoulder) rightHandUpCount++;

                        // Step8：記錄每幀日誌
                        frameLog.append(String.format(Locale.US,
                                "t=%4d L肘%.0f R肘%.0f 肩%.0f 頭%.0f %s%s\n",
                                timeMs, f.leftElbowAngle, f.rightElbowAngle,
                                f.shoulderSlope, f.headYaw,
                                f.leftWristAboveShoulder ? " LH" : "",
                                f.rightWristAboveShoulder ? " RH" : ""));
                    }
                    frame.recycle();
                }

                // Step9：關閉 CSV 寫入器
                try {
                    landmarkWriter.close();
                } catch (Exception ignored) {
                }

                int fp = poseFrames;
                String summary = "分析完成（前 " + limitMs + "ms）\n"
                        + "抽幀=" + sampledFrames + " 偵測到=" + poseFrames + "\n"
                        + (fp > 0
                            ? "平均 L肘=" + Math.round(sumLeftElbow / fp)
                            + " R肘=" + Math.round(sumRightElbow / fp)
                            + " 肩=" + Math.round(sumShoulder / fp)
                            + " 頭=" + Math.round(sumHead / fp) + "\n"
                            + "左手舉幀=" + leftHandUpCount + "/" + fp
                            + " 右手舉幀=" + rightHandUpCount + "/" + fp + "\n"
                            : "")
                        + "CSV 已儲存：" + POSE_ANALYZE_CSV + "\n"
                        + "---- 每幀 ----" + "\n" + frameLog;

                // Step10：寫入日誌檔案並更新 UI
                File logFile = new File(getExternalFilesDir(null), "dance_upperbody_log.txt");
                try (FileWriter w = new FileWriter(logFile)) {
                    w.write(summary);
                }
                runOnUiThread(() -> statusText.setText(summary));
            } catch (Exception e) {
                runOnUiThread(() -> statusText.setText("分析失敗: " + e.getMessage()));
            } finally {
                // Step11：釋放 MediaMetadataRetriever 資源
                try {
                    retriever.release();
                } catch (Exception ignored) {
                }
            }
        });
    }

    /**
     * 把 MediaPipe 的 NormalizedLandmark 列表轉成專案內部的 PoseFrame（純 Java POJO）。
     *
     * <p>保留完整 33 點（PoseAnalyzer 依賴固定 index），缺點補零；
     * 「只看上半身」是在 analyzeBuiltInClip 的報告欄位中聚焦，而非砍掉點。
     *
     * @param timeMs 影片時間戳記（毫秒）
     * @param raw    MediaPipe 偵測到的 NormalizedLandmark 列表
     * @return 轉換後的 PoseFrame
     */
    private PoseFrame toPoseFrame(long timeMs, List<NormalizedLandmark> raw) {
        List<Landmark> landmarks = new ArrayList<>();

        // Step1：依序轉換 33 個 landmark，缺點補零
        for (int id = 0; id < PoseLandmark.COUNT; id++) {
            if (id < raw.size()) {
                NormalizedLandmark lm = raw.get(id);
                landmarks.add(new Landmark(
                        lm.x(), lm.y(), lm.z(),
                        lm.visibility().orElse(0.0f),
                        lm.presence().orElse(0.0f)));
            } else {
                landmarks.add(new Landmark(0f, 0f, 0f, 0f, 0f));
            }
        }

        // Step2：建立 PoseFrame（frameIndex 依時間戳記推導）
        return new PoseFrame((int) (timeMs / FRAME_INTERVAL_MS), timeMs, landmarks);
    }

    /**
     * 讓 Kebbi 說話（TTS）。
     */
    private void speak() {
        if (voiceManager == null || !voiceManager.isInit()) {
            statusText.setText("Nuwa voice SDK is not ready");
            return;
        }

        voiceManager.startTTS("Hello, I am Kebbi.");
        statusText.setText("Speaking");
    }

    /**
     * 讀取 CSV 檔案，對每一幀執行 PoseAnalyzer，
     * 並在 statusText 中印出角度結果，方便確認 MediaPipe 上半身資訊是否穩定。
     */
    private void printPoseAnalyzerResults() {
        File csvFile = new File(getExternalFilesDir(null), POSE_ANALYZE_CSV);
        if (!csvFile.exists()) {
            statusText.setText("找不到 " + POSE_ANALYZE_CSV + "\n請先分析一支影片");
            return;
        }

        statusText.setText("讀取 PoseAnalyzer 結果...");
        executorService.execute(() -> {
            try {
                MotionSequence sequence = PoseIO.readCsv(csvFile);
                int frameCount = sequence.getFrameCount();
                if (frameCount == 0) {
                    runOnUiThread(() -> statusText.setText("CSV 沒有解析出任何影格"));
                    return;
                }

                StringBuilder sb = new StringBuilder();
                int shown = 0;
                int step = Math.max(1, frameCount / 20);

                for (int i = 0; i < frameCount; i += step) {
                    PoseFrame frame = sequence.frames.get(i);
                    PoseFeature f = PoseAnalyzer.analyzeFrame(frame);
                    sb.append(String.format(Locale.US,
                            "Frame %d\nLeft Elbow : %.1f°\nRight Elbow: %.1f°\nShoulder   : %.1f°\nHead Yaw   : %.1f°\nLeft Hand Up : %b\nRight Hand Up: %b\n\n",
                            i, f.leftElbowAngle, f.rightElbowAngle,
                            f.shoulderSlope, f.headYaw,
                            f.leftWristAboveShoulder, f.rightWristAboveShoulder));
                    shown++;
                }

                sb.insert(0, "共 " + frameCount + " 幀，顯示 " + shown + " 幀\n\n");
                runOnUiThread(() -> statusText.setText(sb.toString()));
            } catch (Exception e) {
                runOnUiThread(() -> statusText.setText("PoseAnalyzer 失敗: " + e.getMessage()));
            }
        });
    }

    /**
     * 分析使用者選擇的舞蹈影片。
     *
     * <p>流程：逐幀解析影片 → 寫入 CSV → 偵測舞蹈事件 → 寫入動作計畫 →
     * 執行 round-trip 驗證 → 顯示結果。
     *
     * @param videoUri 影片的 Uri
     */
    private void analyzeDanceVideo(Uri videoUri) {
        statusText.setText("Analyzing dance video...");
        executorService.execute(() -> {
            MediaMetadataRetriever retriever = new MediaMetadataRetriever();
            File landmarkFile = new File(getExternalFilesDir(null), "dance_pose_landmarks.csv");
            File motionPlanFile = new File(getExternalFilesDir(null), "dance_motion_plan.csv");
            List<DanceStep> danceSteps = new ArrayList<>();
            int sampledFrames = 0;
            int poseFrames = 0;
            String lastEventType = "";
            long lastEventTimeMs = Long.MIN_VALUE;

            try (FileWriter landmarkWriter = new FileWriter(landmarkFile, false);
                 FileWriter motionWriter = new FileWriter(motionPlanFile, false)) {

                // Step1：寫入 CSV 標頭
                landmarkWriter.write("time_ms,pose_index,landmark_index,x,y,z,visibility,presence\n");
                motionWriter.write("time_ms,event_type\n");

                // Step2：設定影片來源
                retriever.setDataSource(this, videoUri);
                String durationText = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
                long durationMs = durationText == null ? 0L : Long.parseLong(durationText);

                // Step3：逐幀解析影片
                for (long timeMs = 0; timeMs <= durationMs; timeMs += FRAME_INTERVAL_MS) {
                    Bitmap frame = retriever.getFrameAtTime(timeMs * 1000L, MediaMetadataRetriever.OPTION_CLOSEST);
                    if (frame == null) {
                        continue;
                    }

                    sampledFrames++;

                    // Step4：將 Bitmap 轉為 MPImage 並偵測姿態
                    MPImage image = new BitmapImageBuilder(frame).build();
                    PoseLandmarkerResult result = poseLandmarker.detectForVideo(image, timeMs);
                    if (!result.landmarks().isEmpty()) {
                        poseFrames++;

                        // Step5：寫入 landmark CSV
                        writeLandmarks(landmarkWriter, timeMs, result.landmarks());

                        // Step6：偵測舞蹈事件（帶去抖動閾值）
                        String eventType = detectDanceEvent(result.landmarks().get(0));
                        if (!eventType.isEmpty()
                                && (!eventType.equals(lastEventType)
                                || timeMs - lastEventTimeMs >= MIN_DANCE_EVENT_GAP_MS)) {
                            danceSteps.add(new DanceStep(timeMs, eventType));
                            motionWriter.write(String.format(Locale.US, "%d,%s\n", timeMs, eventType));
                            lastEventType = eventType;
                            lastEventTimeMs = timeMs;
                        }
                    }

                    frame.recycle();
                }

                // Step7：保存結果並執行 round-trip 驗證
                int finalSampledFrames = sampledFrames;
                int finalPoseFrames = poseFrames;
                List<DanceStep> finalDanceSteps = new ArrayList<>(danceSteps);
                latestDanceSteps = finalDanceSteps;

                File jsonFile = new File(getExternalFilesDir(null), "dance_pose_landmarks.json");
                String roundTripStatus = PosePipeline.verify(landmarkFile, jsonFile);

                // Step8：在 UI 上顯示分析結果
                runOnUiThread(() -> statusText.setText(
                        "Pose frames: " + finalPoseFrames + " / " + finalSampledFrames
                                + "\nDance steps: " + finalDanceSteps.size()
                                + "\nLandmarks: " + landmarkFile.getAbsolutePath()
                                + "\nMotion plan: " + motionPlanFile.getAbsolutePath()
                                + "\n" + roundTripStatus));
            } catch (Exception e) {
                runOnUiThread(() -> statusText.setText("Video analysis failed: " + e.getMessage()));
            } finally {
                // Step9：釋放 MediaMetadataRetriever 資源
                try {
                    retriever.release();
                } catch (Exception ignored) {
                    // Nothing useful can be recovered after analysis has already finished.
                }
            }
        });
    }

    /**
     * 將 MediaPipe 的 landmark 列表寫入 CSV。
     *
     * @param writer      FileWriter 目標
     * @param timeMs      時間戳記（毫秒）
     * @param poses       MediaPipe 偵測到的所有 pose 列表
     * @throws Exception 寫入失敗時拋出
     */
    private void writeLandmarks(
            FileWriter writer,
            long timeMs,
            List<List<NormalizedLandmark>> poses) throws Exception {
        // Step1：逐 pose 逐 landmark 寫入 CSV
        for (int poseIndex = 0; poseIndex < poses.size(); poseIndex++) {
            List<NormalizedLandmark> landmarks = poses.get(poseIndex);
            for (int landmarkIndex = 0; landmarkIndex < landmarks.size(); landmarkIndex++) {
                NormalizedLandmark landmark = landmarks.get(landmarkIndex);
                writer.write(String.format(
                        Locale.US,
                        "%d,%d,%d,%f,%f,%f,%f,%f\n",
                        timeMs,
                        poseIndex,
                        landmarkIndex,
                        landmark.x(),
                        landmark.y(),
                        landmark.z(),
                        landmark.visibility().orElse(0.0f),
                        landmark.presence().orElse(0.0f)));
            }
        }
    }

    /**
     * 偵測舞蹈事件類型。
     *
     * <p>根據手腕與肩膀的相對位置，以及身體傾斜方向，判斷當前姿勢事件。
     *
     * @param landmarks MediaPipe 偵測到的 33 個 NormalizedLandmark
     * @return 事件類型字串（LEFT_HAND_UP / RIGHT_HAND_UP / BOTH_HANDS_UP /
     *         LEAN_LEFT / LEAN_RIGHT / 空字串）
     */
    private String detectDanceEvent(List<NormalizedLandmark> landmarks) {
        if (landmarks.size() <= 24) {
            return "";
        }

        // Step1：取出關鍵 landmark
        NormalizedLandmark leftShoulder = landmarks.get(11);
        NormalizedLandmark rightShoulder = landmarks.get(12);
        NormalizedLandmark leftWrist = landmarks.get(15);
        NormalizedLandmark rightWrist = landmarks.get(16);
        NormalizedLandmark leftHip = landmarks.get(23);
        NormalizedLandmark rightHip = landmarks.get(24);

        // Step2：判斷手是否高於肩膀
        boolean leftHandHigh = isVisible(leftWrist) && leftWrist.y() < leftShoulder.y() - 0.08f;
        boolean rightHandHigh = isVisible(rightWrist) && rightWrist.y() < rightShoulder.y() - 0.08f;

        // Step3：計算身體傾斜量
        float shoulderCenterX = (leftShoulder.x() + rightShoulder.x()) / 2f;
        float hipCenterX = (leftHip.x() + rightHip.x()) / 2f;
        float bodyLean = shoulderCenterX - hipCenterX;

        // Step4：由高到低優先判斷事件類型
        if (leftHandHigh && rightHandHigh) {
            return "BOTH_HANDS_UP";
        }
        if (leftHandHigh) {
            return "LEFT_HAND_UP";
        }
        if (rightHandHigh) {
            return "RIGHT_HAND_UP";
        }
        if (bodyLean < -0.05f) {
            return "LEAN_LEFT";
        }
        if (bodyLean > 0.05f) {
            return "LEAN_RIGHT";
        }
        return "";
    }

    /**
     * 判斷 landmark 是否可見（visibility 和 presence 均大於等於閾值）。
     *
     * @param landmark 要檢查的 landmark
     * @return 若可見回傳 true
     */
    private boolean isVisible(NormalizedLandmark landmark) {
        return landmark.visibility().orElse(1.0f) >= 0.45f
                && landmark.presence().orElse(1.0f) >= 0.45f;
    }

    /**
     * 播放舞蹈動作（根據 latestDanceSteps 中的事件序列）。
     *
     * <p>若無舞蹈步驟，播放第一個可用的 motion；
     * 若機器人 SDK 未準備，顯示提示。
     */
    private void playDanceMotion() {
        if (robotManager == null || !robotManager.isInit()) {
            statusText.setText("Nuwa robot SDK is not ready");
            return;
        }

        List<DanceStep> steps = latestDanceSteps;
        if (steps == null || steps.isEmpty()) {
            playFirstAvailableMotion();
            return;
        }

        // Step1：建立事件到 motion 的對應表
        List<String> motions = robotManager.getMotionList();
        if (motions == null || motions.isEmpty()) {
            statusText.setText("No motion found on this Kebbi");
            return;
        }

        Map<String, String> eventToMotion = MotionPoseMap.buildEventToMotionMap(motions);

        // Step2：逐步驟播放舞蹈
        dancePlaybackStopped = false;
        statusText.setText("Playing dance steps: " + steps.size());
        executorService.execute(() -> {
            for (int i = 0; i < steps.size(); i++) {
                if (dancePlaybackStopped) {
                    break;
                }

                DanceStep step = steps.get(i);
                String motionName = eventToMotion.getOrDefault(step.eventType, motions.get(0));
                int stepNumber = i + 1;
                runOnUiThread(() -> statusText.setText(
                        "Dance " + stepNumber + " / " + steps.size()
                                + "\n" + step.eventType
                                + "\nPlaying: " + motionName));
                robotManager.motionPlay(motionName, false);

                // Step3：等待動作完成
                try {
                    Thread.sleep(MOTION_STEP_MS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }

            // Step4：播放完成後更新 UI
            if (!dancePlaybackStopped) {
                runOnUiThread(() -> statusText.setText("Dance playback finished"));
            }
        });
    }

    /**
     * 彈出選單列出機器人上所有姿勢動作，選一個就直接播出。
     *
     * <p>方便你一支一支把數值與實際動作做校正對照。
     */
    private void showMotionPicker() {
        if (robotManager == null || !robotManager.isInit()) {
            statusText.setText("Nuwa robot SDK is not ready");
            return;
        }

        List<String> motions = robotManager.getMotionList();
        if (motions == null || motions.isEmpty()) {
            statusText.setText("No motion found on this Kebbi");
            return;
        }

        // Step1：將 motion 列表轉為陣列
        String[] motionArray = motions.toArray(new String[0]);

        // Step2：彈出選單並處理選擇結果
        new AlertDialog.Builder(this)
                .setTitle("選擇姿勢動作")
                .setItems(motionArray, (dialog, which) -> {
                    String motionName = motionArray[which];
                    robotManager.motionPlay(motionName, false);
                    String event = MotionPoseMap.eventForMotion(motions, motionName);
                    statusText.setText("Playing: " + motionName
                            + "\n對應事件: " + event
                            + "\n(" + (which + 1) + "/" + motions.size() + ")");
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /**
     * 播放機器人第一個可用的內建 motion。
     */
    private void playFirstAvailableMotion() {
        if (robotManager == null || !robotManager.isInit()) {
            statusText.setText("Nuwa robot SDK is not ready");
            return;
        }

        List<String> motions = robotManager.getMotionList();
        if (motions == null || motions.isEmpty()) {
            statusText.setText("No motion found on this Kebbi");
            return;
        }

        String motionName = motions.get(0);
        robotManager.motionPlay(motionName, false);
        statusText.setText("Playing: " + motionName);
    }

    /**
     * 測試機器人內建 motion（依序播放前 5 支）。
     */
    private void testBuiltInMotions() {
        if (robotManager == null || !robotManager.isInit()) {
            statusText.setText("Nuwa robot SDK is not ready");
            return;
        }

        List<String> motions = robotManager.getMotionList();
        if (motions == null || motions.isEmpty()) {
            statusText.setText("No motion found on this Kebbi");
            return;
        }

        // Step1：計算要測試的 motion 數量
        int testCount = Math.min(MOTION_TEST_COUNT, motions.size());
        dancePlaybackStopped = false;
        statusText.setText("Testing built-in motions: " + testCount);

        // Step2：依序播放每支 motion
        executorService.execute(() -> {
            for (int i = 0; i < testCount; i++) {
                if (dancePlaybackStopped) {
                    break;
                }

                String motionName = motions.get(i);
                int stepNumber = i + 1;
                runOnUiThread(() -> statusText.setText(
                        "Motion test " + stepNumber + " / " + testCount
                                + "\nPlaying: " + motionName));
                robotManager.motionPlay(motionName, false);

                // Step3：等待動作完成
                try {
                    Thread.sleep(MOTION_STEP_MS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }

            // Step4：測試完成後更新 UI
            if (!dancePlaybackStopped) {
                runOnUiThread(() -> statusText.setText("Motion test finished"));
            }
        });
    }

    /**
     * 停止當前播放的動作和 TTS。
     */
    private void stopMotion() {
        dancePlaybackStopped = true;
        if (robotManager != null && robotManager.isInit()) {
            robotManager.motionStop(true);
        }
        if (voiceManager != null && voiceManager.isInit()) {
            voiceManager.stopTTS();
        }
        statusText.setText("Stopped");
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();

        // Step1：關閉 PoseLandmarker
        if (poseLandmarker != null) {
            poseLandmarker.close();
        }

        // Step2：關閉執行緒池
        if (executorService != null) {
            executorService.shutdownNow();
        }

        // Step3：釋放 Nuwa SDK 資源
        if (apiManager != null && apiManager.isInit()) {
            apiManager.release();
        }
    }

    /**
     * 舞蹈步驟的內部資料類。
     *
     * <p>記錄每個舞蹈事件的發生時間與事件類型。
     */
    private static class DanceStep {
        /** 事件發生時間（毫秒）。 */
        final long timeMs;
        /** 事件類型（LEFT_HAND_UP / RIGHT_HAND_UP / BOTH_HANDS_UP 等）。 */
        final String eventType;

        DanceStep(long timeMs, String eventType) {
            this.timeMs = timeMs;
            this.eventType = eventType;
        }
    }
}
