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

    private NuwaRobotManager robotManager;
    private NuwaVoiceManager voiceManager;
    private ApiManager apiManager;
    private PoseLandmarker poseLandmarker;
    private TextView statusText;
    private ExecutorService executorService;
    private ActivityResultLauncher<Intent> pickVideoLauncher;
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

        apiManager = ApiManager.getInstance().init(this, getPackageName());
        robotManager = NuwaRobotManager.getInstance().init(this);
        voiceManager = NuwaVoiceManager.getInstance().init(this);
        executorService = Executors.newSingleThreadExecutor();
        setupPoseLandmarker();
        setupVideoPicker();

        Button speakButton = findViewById(R.id.speakButton);
        Button pickVideoButton = findViewById(R.id.pickVideoButton);
        Button motionButton = findViewById(R.id.motionButton);
        Button testMotionsButton = findViewById(R.id.testMotionsButton);
        Button stopButton = findViewById(R.id.stopButton);
        Button cameraTestButton = findViewById(R.id.cameraTestButton);

        speakButton.setOnClickListener(v -> speak());
        pickVideoButton.setOnClickListener(v -> analyzeBuiltInClip());
        motionButton.setOnClickListener(v -> showMotionPicker());
        testMotionsButton.setOnClickListener(v -> testBuiltInMotions());
        stopButton.setOnClickListener(v -> stopMotion());
        cameraTestButton.setOnClickListener(v -> startActivity(new Intent(this, CameraTestActivity.class)));
    }

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
     * 只解析前 10 秒、只取上半身關鍵點，逐幀用 PoseAnalyzer 算角度，
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
                retriever.setDataSource(clip.getAbsolutePath());
                String durationText = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
                long durationMs = durationText == null ? 0L : Long.parseLong(durationText);
                long limitMs = Math.min(durationMs, CLIP_ANALYZE_MS);

                for (long timeMs = 0; timeMs <= limitMs; timeMs += FRAME_INTERVAL_MS) {
                    Bitmap frame = retriever.getFrameAtTime(timeMs * 1000L, MediaMetadataRetriever.OPTION_CLOSEST);
                    if (frame == null) {
                        continue;
                    }
                    sampledFrames++;
                    MPImage image = new BitmapImageBuilder(frame).build();
                    PoseLandmarkerResult result = poseLandmarker.detectForVideo(image, timeMs);
                    if (!result.landmarks().isEmpty()) {
                        poseFrames++;
                        List<NormalizedLandmark> raw = result.landmarks().get(0);
                        PoseFrame pf = toPoseFrame(timeMs, raw);
                        PoseFeature f = PoseAnalyzer.analyzeFrame(pf);
                        sumLeftElbow += f.leftElbowAngle;
                        sumRightElbow += f.rightElbowAngle;
                        sumShoulder += f.shoulderSlope;
                        sumHead += f.headYaw;
                        if (f.leftWristAboveShoulder) leftHandUpCount++;
                        if (f.rightWristAboveShoulder) rightHandUpCount++;
                        frameLog.append(String.format(Locale.US,
                                "t=%4d L肘%.0f R肘%.0f 肩%.0f 頭%.0f %s%s\n",
                                timeMs, f.leftElbowAngle, f.rightElbowAngle,
                                f.shoulderSlope, f.headYaw,
                                f.leftWristAboveShoulder ? " LH" : "",
                                f.rightWristAboveShoulder ? " RH" : ""));
                    }
                    frame.recycle();
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
                        + "---- 每幀 ----" + "\n" + frameLog;

                File logFile = new File(getExternalFilesDir(null), "dance_upperbody_log.txt");
                try (FileWriter w = new FileWriter(logFile)) {
                    w.write(summary);
                }
                runOnUiThread(() -> statusText.setText(summary));
            } catch (Exception e) {
                runOnUiThread(() -> statusText.setText("分析失敗: " + e.getMessage()));
            } finally {
                try {
                    retriever.release();
                } catch (Exception ignored) {
                }
            }
        });
    }

    /**
     * 把 MediaPipe 的 NormalizedLandmark 列表轉成專案內部的 PoseFrame（純 Java POJO）。
     * 保留完整 33 點（PoseAnalyzer 依賴固定 index），缺點補零；
     * 「只看上半身」是在 analyzeBuiltInClip 的報告欄位中聚焦，而非砍掉點。
     */
    private PoseFrame toPoseFrame(long timeMs, List<NormalizedLandmark> raw) {
        List<Landmark> landmarks = new ArrayList<>();
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
        return new PoseFrame((int) (timeMs / FRAME_INTERVAL_MS), timeMs, landmarks);
    }

    private void speak() {
        if (voiceManager == null || !voiceManager.isInit()) {
            statusText.setText("Nuwa voice SDK is not ready");
            return;
        }

        voiceManager.startTTS("Hello, I am Kebbi.");
        statusText.setText("Speaking");
    }

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
                landmarkWriter.write("time_ms,pose_index,landmark_index,x,y,z,visibility,presence\n");
                motionWriter.write("time_ms,event_type\n");
                retriever.setDataSource(this, videoUri);
                String durationText = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
                long durationMs = durationText == null ? 0L : Long.parseLong(durationText);

                for (long timeMs = 0; timeMs <= durationMs; timeMs += FRAME_INTERVAL_MS) {
                    Bitmap frame = retriever.getFrameAtTime(timeMs * 1000L, MediaMetadataRetriever.OPTION_CLOSEST);
                    if (frame == null) {
                        continue;
                    }

                    sampledFrames++;
                    MPImage image = new BitmapImageBuilder(frame).build();
                    PoseLandmarkerResult result = poseLandmarker.detectForVideo(image, timeMs);
                    if (!result.landmarks().isEmpty()) {
                        poseFrames++;
                        writeLandmarks(landmarkWriter, timeMs, result.landmarks());

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

                int finalSampledFrames = sampledFrames;
                int finalPoseFrames = poseFrames;
                List<DanceStep> finalDanceSteps = new ArrayList<>(danceSteps);
                latestDanceSteps = finalDanceSteps;

                File jsonFile = new File(getExternalFilesDir(null), "dance_pose_landmarks.json");
                String roundTripStatus = PosePipeline.verify(landmarkFile, jsonFile);

                runOnUiThread(() -> statusText.setText(
                        "Pose frames: " + finalPoseFrames + " / " + finalSampledFrames
                                + "\nDance steps: " + finalDanceSteps.size()
                                + "\nLandmarks: " + landmarkFile.getAbsolutePath()
                                + "\nMotion plan: " + motionPlanFile.getAbsolutePath()
                                + "\n" + roundTripStatus));
            } catch (Exception e) {
                runOnUiThread(() -> statusText.setText("Video analysis failed: " + e.getMessage()));
            } finally {
                try {
                    retriever.release();
                } catch (Exception ignored) {
                    // Nothing useful can be recovered after analysis has already finished.
                }
            }
        });
    }

    private void writeLandmarks(
            FileWriter writer,
            long timeMs,
            List<List<NormalizedLandmark>> poses) throws Exception {
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

    private String detectDanceEvent(List<NormalizedLandmark> landmarks) {
        if (landmarks.size() <= 24) {
            return "";
        }

        NormalizedLandmark leftShoulder = landmarks.get(11);
        NormalizedLandmark rightShoulder = landmarks.get(12);
        NormalizedLandmark leftWrist = landmarks.get(15);
        NormalizedLandmark rightWrist = landmarks.get(16);
        NormalizedLandmark leftHip = landmarks.get(23);
        NormalizedLandmark rightHip = landmarks.get(24);

        boolean leftHandHigh = isVisible(leftWrist) && leftWrist.y() < leftShoulder.y() - 0.08f;
        boolean rightHandHigh = isVisible(rightWrist) && rightWrist.y() < rightShoulder.y() - 0.08f;
        float shoulderCenterX = (leftShoulder.x() + rightShoulder.x()) / 2f;
        float hipCenterX = (leftHip.x() + rightHip.x()) / 2f;
        float bodyLean = shoulderCenterX - hipCenterX;

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

    private boolean isVisible(NormalizedLandmark landmark) {
        return landmark.visibility().orElse(1.0f) >= 0.45f
                && landmark.presence().orElse(1.0f) >= 0.45f;
    }

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

        List<String> motions = robotManager.getMotionList();
        if (motions == null || motions.isEmpty()) {
            statusText.setText("No motion found on this Kebbi");
            return;
        }

        Map<String, String> eventToMotion = MotionPoseMap.buildEventToMotionMap(motions);

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

                try {
                    Thread.sleep(MOTION_STEP_MS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }

            if (!dancePlaybackStopped) {
                runOnUiThread(() -> statusText.setText("Dance playback finished"));
            }
        });
    }

    /**
     * 點「Play Dance Motion」改為：彈出選單列出機器人上所有姿勢動作，
     * 選一個就直接播出，方便你一支一支把數值與實際動作做校正對照。
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

        String[] motionArray = motions.toArray(new String[0]);
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

        int testCount = Math.min(MOTION_TEST_COUNT, motions.size());
        dancePlaybackStopped = false;
        statusText.setText("Testing built-in motions: " + testCount);
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

                try {
                    Thread.sleep(MOTION_STEP_MS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }

            if (!dancePlaybackStopped) {
                runOnUiThread(() -> statusText.setText("Motion test finished"));
            }
        });
    }

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
        if (poseLandmarker != null) {
            poseLandmarker.close();
        }
        if (executorService != null) {
            executorService.shutdownNow();
        }
        if (apiManager != null && apiManager.isInit()) {
            apiManager.release();
        }
    }

    private static class DanceStep {
        final long timeMs;
        final String eventType;

        DanceStep(long timeMs, String eventType) {
            this.timeMs = timeMs;
            this.eventType = eventType;
        }
    }
}
