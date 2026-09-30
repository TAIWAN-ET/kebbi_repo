package com.example.myapplication.analysis;

import android.content.Context;
import android.graphics.Bitmap;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.util.Log;

import com.example.myapplication.MainActivity;
import com.example.myapplication.io.PoseIO;
import com.example.myapplication.io.PosePipeline;
import com.example.myapplication.math.PoseMath;
import com.example.myapplication.model.Landmark;
import com.example.myapplication.model.KeyframeEntry;
import com.example.myapplication.model.PoseFeature;
import com.example.myapplication.model.PoseFrame;
import com.example.myapplication.model.PoseLandmark;
import com.example.myapplication.robot.RobotController;
import com.google.mediapipe.framework.image.BitmapImageBuilder;
import com.google.mediapipe.framework.image.MPImage;
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark;
import com.google.mediapipe.tasks.core.BaseOptions;
import com.google.mediapipe.tasks.vision.core.RunningMode;
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarker;
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarkerResult;

import java.io.File;
import java.io.FileWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;

/**
 * 影片姿態分析封裝（MediaPipe）。
 *
 * <p>用途：把「影片 → MediaPipe Pose Landmarker → PoseFrame → PoseAnalyzer →
 * 舞蹈事件偵測 → CSV/Log 摘要」全部集中在這裡，
 * 讓 {@link MainActivity} 只負責 UI 與流程編排。
 * 之後若換模型（MoveNet / OpenPose）也只改這層。
 *
 * <p>誰會呼叫它：
 * {@link com.example.myapplication.MainActivity} 在使用者按「分析影片」或選擇影片時呼叫
 * {@link #analyzeBuiltInClip(ResultListener)} / {@link #analyzeDanceVideo(Uri, ResultListener)}，
 * 分析完成透過 {@link ResultListener} 回傳摘要、PoseFeature 列表與舞蹈步驟。
 *
 * <p>不負責什麼：
 * 不控制機器人（由 {@link RobotController} 負責），
 * 不做資料序列化（由 {@link PoseIO} 負責），
 * 不做 UI（只在背景執行緒跑，由呼叫端把結果丟回主執行緒）。
 */
public class VideoAnalyzer {

    /**
     * 分析結果回呼。
     */
    public interface ResultListener {
        /**
         * 分析完成。
         *
         * @param summary     顯示用的摘要字串
         * @param features    每幀的人體特徵（供 RobotMapper 映射用）
         * @param danceSteps  舞蹈步驟列表；內建影片分析（無事件偵測）時為 null
         */
        void onAnalyzed(String summary, List<PoseFeature> features, List<DanceStep> danceSteps,
                List<KeyframeEntry> keyframes);

        /**
         * 分析失敗。
         *
         * @param message 錯誤訊息
         */
        void onError(String message);
    }

    /**
     * 舞蹈步驟的資料類。
     *
     * <p>記錄每個舞蹈事件的發生時間與事件類型。
     */
    public static class DanceStep {
        /** 事件發生時間（毫秒）。 */
        public final long timeMs;
        /** 事件類型（LEFT_HAND_UP / RIGHT_HAND_UP / BOTH_HANDS_UP 等）。 */
        public final String eventType;

        public DanceStep(long timeMs, String eventType) {
            this.timeMs = timeMs;
            this.eventType = eventType;
        }
    }

    private static final long FRAME_INTERVAL_MS = 200L;
    private static final long MIN_DANCE_EVENT_GAP_MS = 600L;
    // 離線分析用：只解析前 10 秒、只取上半身關鍵點
    private static final long CLIP_ANALYZE_MS = 10000L;
    private static final String BUILTIN_CLIP = "dance_input.mp4";
    // PoseAnalyzer 結果輸出用的 CSV 檔案名稱
    private static final String POSE_ANALYZE_CSV = "dance_pose_landmarks.csv";
    private static final String KEYFRAME_CSV = "dance_keyframes.csv";
    private static final String POSE_MODEL = "pose_landmarker_lite.task";

    /** 座標平滑係數：越小越平滑，越大越跟得上快動作。 */
    private static final float SMOOTHING_ALPHA = 0.5f;

    private final Context context;
    private final ExecutorService executorService;

    /**
     * @param context         Activity 或 Application Context
     * @param executorService 背景執行緒池
     */
    public VideoAnalyzer(Context context, ExecutorService executorService) {
        this.context = context;
        this.executorService = executorService;
    }

    /**
     * 建立一個 MediaPipe Pose Landmarker（VIDEO 模式）。
     *
     * <p><b>每次分析都要建立新的、用完就 close。</b>
     * MediaPipe 官方文件規定 VIDEO 模式的 {@code detectForVideo(image, timestampMs)}
     * 時間戳必須單調遞增。
     * 舊版在建構子建立<b>一個</b>共用的 landmarker，
     * 而每支影片的 timeMs 都是從 0 開始 ——
     * 分析完第一支再分析第二支時，時間戳從 10000 倒退回 0，
     * MediaPipe 會直接拋例外，使用者只看到「分析失敗」，
     * 必須重啟 App 才能分析下一支影片。
     *
     * @return 已建立的 PoseLandmarker
     */
    private PoseLandmarker setupPoseLandmarker() {
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

        return PoseLandmarker.createFromOptions(context, options);
    }

    /**
     * 釋放資源。
     *
     * <p>PoseLandmarker 現在是每次分析各自建立、在 finally 區塊關閉，
     * 這裡不再持有共用實例，保留這個方法只是維持既有呼叫端
     * （MainActivity.onDestroy）不用改。
     */
    public void close() {
        // 無共用資源需要釋放。
    }

    /**
     * 分析內建影片（需先用 adb push 放到 App 外部檔案目錄的 dance_input.mp4）。
     *
     * <p>只解析前 10 秒、只取上半身關鍵點，逐幀用 PoseAnalyzer 算角度，
     * 最後把每幀摘要與整段統計（含角度 min~max 範圍）顯示在 summary，
     * 方便先看「這支影片數據長怎樣」。
     *
     * @param listener 結果回呼
     */
    public void analyzeBuiltInClip(ResultListener listener) {
        File clip = new File(context.getExternalFilesDir(null), BUILTIN_CLIP);
        if (!clip.exists()) {
            listener.onError("找不到 " + BUILTIN_CLIP + "\n請先:\nadb push 影片 "
                    + context.getExternalFilesDir(null).getAbsolutePath() + "/" + BUILTIN_CLIP);
            return;
        }

        executorService.execute(() -> {
            MediaMetadataRetriever retriever = new MediaMetadataRetriever();
            PoseLandmarker poseLandmarker = null;
            List<Landmark> prevNorm = null;
            List<Landmark> prevWorld = null;
            int sampledFrames = 0;
            int poseFrames = 0;
            float sumLeftElbow = 0f, sumRightElbow = 0f, sumShoulder = 0f, sumHead = 0f;
            float minLeftElbow = Float.POSITIVE_INFINITY, maxLeftElbow = Float.NEGATIVE_INFINITY;
            float minRightElbow = Float.POSITIVE_INFINITY, maxRightElbow = Float.NEGATIVE_INFINITY;
            float minShoulder = Float.POSITIVE_INFINITY, maxShoulder = Float.NEGATIVE_INFINITY;
            float minHead = Float.POSITIVE_INFINITY, maxHead = Float.NEGATIVE_INFINITY;
            int leftHandUpCount = 0, rightHandUpCount = 0;
            StringBuilder frameLog = new StringBuilder();
            List<PoseFeature> features = new ArrayList<>();
            List<Long> featureTimes = new ArrayList<>();

            try {
                // Step0：本次分析專用的 Landmarker（時間戳從 0 開始，不會和上一次衝突）
                poseLandmarker = setupPoseLandmarker();

                // Step1：設定影片來源
                retriever.setDataSource(clip.getAbsolutePath());

                // Step2：取得影片長度並限制為前 10 秒
                String durationText = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
                long durationMs = durationText == null ? 0L : Long.parseLong(durationText);
                long limitMs = Math.min(durationMs, CLIP_ANALYZE_MS);

                // Step3：準備 CSV 寫入器
                File csvFile = new File(context.getExternalFilesDir(null), POSE_ANALYZE_CSV);
                FileWriter landmarkWriter = new FileWriter(csvFile, false);
                landmarkWriter.write("time_ms,pose_index,landmark_index,x,y,z,visibility,presence\n");

                // Step4：逐幀解析影片
                for (long timeMs = 0; timeMs <= limitMs; timeMs += FRAME_INTERVAL_MS) {
                    Bitmap frame = retriever.getFrameAtTime(timeMs * 1000L, MediaMetadataRetriever.OPTION_CLOSEST);
                    if (frame == null) {
                        continue;
                    }
                    sampledFrames++;

                    // Step5：確保 Bitmap 為 ARGB_8888 格式（MediaPipe 要求）
                    Bitmap argbFrame = frame.copy(Bitmap.Config.ARGB_8888, true);
                    frame.recycle();

                    // Step6：將 Bitmap 轉為 MediaPipe MPImage
                    MPImage image = new BitmapImageBuilder(argbFrame).build();

                    // Step7：用 PoseLandmarker 偵測姿態
                    PoseLandmarkerResult result = poseLandmarker.detectForVideo(image, timeMs);
                    argbFrame.recycle();
                    if (!result.landmarks().isEmpty()) {
                        poseFrames++;

                        // Step7：轉換為 PoseFrame 並計算特徵
                        List<NormalizedLandmark> raw = result.landmarks().get(0);
                        PoseFrame pf = toPoseFrame(timeMs, raw, worldOf(result));
                        // 逐幀低通濾波：MediaPipe 的輸出有抖動，不平滑會讓馬達抽搐
                        pf.landmarks = smooth(pf.landmarks, prevNorm);
                        pf.worldLandmarks = smooth(pf.worldLandmarks, prevWorld);
                        prevNorm = pf.landmarks;
                        prevWorld = pf.worldLandmarks;
                        PoseFeature f = PoseAnalyzer.analyzeFrame(pf);
                        features.add(f);
                        featureTimes.add(timeMs);

                        // Step8：寫入 CSV
                        writeLandmarks(landmarkWriter, timeMs, result.landmarks());

                        // Step9：累計角度統計
                        sumLeftElbow += f.leftElbowAngle;
                        sumRightElbow += f.rightElbowAngle;
                        sumShoulder += f.shoulderSlope;
                        sumHead += f.headYaw;
                        minLeftElbow = Math.min(minLeftElbow, f.leftElbowAngle);
                        maxLeftElbow = Math.max(maxLeftElbow, f.leftElbowAngle);
                        minRightElbow = Math.min(minRightElbow, f.rightElbowAngle);
                        maxRightElbow = Math.max(maxRightElbow, f.rightElbowAngle);
                        minShoulder = Math.min(minShoulder, f.shoulderSlope);
                        maxShoulder = Math.max(maxShoulder, f.shoulderSlope);
                        minHead = Math.min(minHead, f.headYaw);
                        maxHead = Math.max(maxHead, f.headYaw);
                        if (f.leftWristAboveShoulder) leftHandUpCount++;
                        if (f.rightWristAboveShoulder) rightHandUpCount++;

                        // Debug：各層關鍵數值輸出（MediaPipe → PoseAnalyzer）
                        Log.i("PoseDebug", String.format(Locale.US,
                                "t=%d %s", timeMs, f.describe()));

                        // Step8：記錄每幀日誌
                        frameLog.append(String.format(Locale.US,
                                "t=%4d L肘%.0f R肘%.0f 肩%.0f 頭%.0f %s%s\n",
                                timeMs, f.leftElbowAngle, f.rightElbowAngle,
                                f.shoulderSlope, f.headYaw,
                                f.leftWristAboveShoulder ? " LH" : "",
                                f.rightWristAboveShoulder ? " RH" : ""));
                    }
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
                            + "範圍 L肘=" + Math.round(minLeftElbow) + "~" + Math.round(maxLeftElbow)
                            + " R肘=" + Math.round(minRightElbow) + "~" + Math.round(maxRightElbow)
                            + " 肩=" + Math.round(minShoulder) + "~" + Math.round(maxShoulder)
                            + " 頭=" + Math.round(minHead) + "~" + Math.round(maxHead) + "\n"
                            + "左手舉幀=" + leftHandUpCount + "/" + fp
                            + " 右手舉幀=" + rightHandUpCount + "/" + fp + "\n"
                            : "")
                        + "CSV 已儲存：" + POSE_ANALYZE_CSV + "\n"
                        + "---- 每幀 ----" + "\n" + frameLog;

                // Step10：寫入日誌檔案
                File logFile = new File(context.getExternalFilesDir(null), "dance_upperbody_log.txt");
                try (FileWriter w = new FileWriter(logFile)) {
                    w.write(summary);
                }
                List<KeyframeEntry> keyframes = buildKeyframes(features, featureTimes, limitMs);
                File keyframeFile = new File(context.getExternalFilesDir(null), KEYFRAME_CSV);
                try (FileWriter keyframeWriter = new FileWriter(keyframeFile, false)) {
                    writeKeyframes(keyframeWriter, keyframes);
                } catch (Exception ignored) {
                    // Keep the pose analysis result even if keyframe export fails.
                }
                listener.onAnalyzed(summary, features, null, keyframes);
            } catch (Exception e) {
                listener.onError("分析失敗: " + e.getMessage());
            } finally {
                // Step11：釋放 MediaMetadataRetriever 與 PoseLandmarker 資源
                try {
                    retriever.release();
                } catch (Exception ignored) {
                }
                if (poseLandmarker != null) {
                    try {
                        poseLandmarker.close();
                    } catch (Exception ignored) {
                    }
                }
            }
        });
    }

    /**
     * 分析使用者選擇的舞蹈影片。
     *
     * <p>流程：逐幀解析影片 → 寫入 CSV → 偵測舞蹈事件 → 寫入動作計畫 →
     * 執行 round-trip 驗證 → 回傳摘要與舞蹈步驟。
     *
     * @param videoUri 影片的 Uri
     * @param listener 結果回呼
     */
    public void analyzeDanceVideo(Uri videoUri, ResultListener listener) {
        executorService.execute(() -> {
            MediaMetadataRetriever retriever = new MediaMetadataRetriever();
            PoseLandmarker poseLandmarker = null;
            List<Landmark> prevNorm = null;
            List<Landmark> prevWorld = null;
            File landmarkFile = new File(context.getExternalFilesDir(null), "dance_pose_landmarks.csv");
            File motionPlanFile = new File(context.getExternalFilesDir(null), "dance_motion_plan.csv");
            List<DanceStep> danceSteps = new ArrayList<>();
            List<PoseFeature> features = new ArrayList<>();
            List<Long> featureTimes = new ArrayList<>();
            int sampledFrames = 0;
            int poseFrames = 0;
            String lastEventType = "";
            long lastEventTimeMs = Long.MIN_VALUE;

            try (FileWriter landmarkWriter = new FileWriter(landmarkFile, false);
                 FileWriter motionWriter = new FileWriter(motionPlanFile, false)) {

                // Step1：寫入 CSV 標頭
                landmarkWriter.write("time_ms,pose_index,landmark_index,x,y,z,visibility,presence\n");
                motionWriter.write("time_ms,event_type\n");

                // Step1b：本次分析專用的 Landmarker
                poseLandmarker = setupPoseLandmarker();

                // Step2：設定影片來源
                retriever.setDataSource(context, videoUri);
                String durationText = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
                long durationMs = durationText == null ? 0L : Long.parseLong(durationText);

                // Step3：逐幀解析影片
                for (long timeMs = 0; timeMs <= durationMs; timeMs += FRAME_INTERVAL_MS) {
                    Bitmap frame = retriever.getFrameAtTime(timeMs * 1000L, MediaMetadataRetriever.OPTION_CLOSEST);
                    if (frame == null) {
                        continue;
                    }

                    sampledFrames++;

                    // Step4：確保 Bitmap 為 ARGB_8888 格式（MediaPipe 要求）
                    Bitmap argbFrame = frame.copy(Bitmap.Config.ARGB_8888, true);
                    frame.recycle();

                    // Step5：將 Bitmap 轉為 MPImage 並偵測姿態
                    MPImage image = new BitmapImageBuilder(argbFrame).build();
                    PoseLandmarkerResult result = poseLandmarker.detectForVideo(image, timeMs);
                    argbFrame.recycle();
                    if (!result.landmarks().isEmpty()) {
                        poseFrames++;

                        // Step5：寫入 landmark CSV
                        writeLandmarks(landmarkWriter, timeMs, result.landmarks());

                        // Step5b：轉換為 PoseFeature 供 RobotMapper 映射
                        PoseFrame pf = toPoseFrame(timeMs, result.landmarks().get(0), worldOf(result));
                        pf.landmarks = smooth(pf.landmarks, prevNorm);
                        pf.worldLandmarks = smooth(pf.worldLandmarks, prevWorld);
                        prevNorm = pf.landmarks;
                        prevWorld = pf.worldLandmarks;
                        features.add(PoseAnalyzer.analyzeFrame(pf));
                        featureTimes.add(timeMs);

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
                }

                // Step7：執行 round-trip 驗證
                File jsonFile = new File(context.getExternalFilesDir(null), "dance_pose_landmarks.json");
                String roundTripStatus = PosePipeline.verify(landmarkFile, jsonFile);

                // Step8：回傳結果
                int finalSampledFrames = sampledFrames;
                int finalPoseFrames = poseFrames;
                List<DanceStep> finalDanceSteps = new ArrayList<>(danceSteps);
                List<PoseFeature> finalFeatures = features;
                String summary = "Pose frames: " + finalPoseFrames + " / " + finalSampledFrames
                        + "\nDance steps: " + finalDanceSteps.size()
                        + "\nLandmarks: " + landmarkFile.getAbsolutePath()
                        + "\nMotion plan: " + motionPlanFile.getAbsolutePath()
                        + "\n" + roundTripStatus;
                List<KeyframeEntry> keyframes = buildKeyframes(finalFeatures, featureTimes, durationMs);
                File keyframeFile = new File(context.getExternalFilesDir(null), KEYFRAME_CSV);
                try (FileWriter keyframeWriter = new FileWriter(keyframeFile, false)) {
                    writeKeyframes(keyframeWriter, keyframes);
                } catch (Exception ignored) {
                    // Keep the pose analysis result even if keyframe export fails.
                }
                listener.onAnalyzed(summary, finalFeatures, finalDanceSteps, keyframes);
            } catch (Exception e) {
                listener.onError("Video analysis failed: " + e.getMessage());
            } finally {
                // Step9：釋放 MediaMetadataRetriever 與 PoseLandmarker 資源
                try {
                    retriever.release();
                } catch (Exception ignored) {
                    // Nothing useful can be recovered after analysis has already finished.
                }
                if (poseLandmarker != null) {
                    try {
                        poseLandmarker.close();
                    } catch (Exception ignored) {
                    }
                }
            }
        });
    }

    /**
     * 把 MediaPipe 的 NormalizedLandmark 列表轉成專案內部的 PoseFrame（純 Java POJO）。
     *
     * <p>保留完整 33 點（PoseAnalyzer 依賴固定 index），缺點補零；
     * 「只看上半身」是在分析報告欄位中聚焦，而非砍掉點。
     *
     * @param timeMs 影片時間戳記（毫秒）
     * @param raw    MediaPipe 偵測到的 NormalizedLandmark 列表
     * @return 轉換後的 PoseFrame
     */
    private PoseFrame toPoseFrame(long timeMs, List<NormalizedLandmark> raw,
            List<com.google.mediapipe.tasks.components.containers.Landmark> rawWorld) {
        List<Landmark> landmarks = new ArrayList<>();
        List<Landmark> worldLandmarks = new ArrayList<>();

        // Step1：依序轉換 33 個 landmark，缺點補零
        for (int id = 0; id < PoseLandmark.COUNT; id++) {
            float visibility = 0f;
            float presence = 0f;
            if (id < raw.size()) {
                NormalizedLandmark lm = raw.get(id);
                // MediaPipe 的 visibility / presence 是 Optional，pose 模型不一定會填。
                // 「沒回報」要當成「正常」而不是 0，否則可信度檢查會把每一幀都判成不可信。
                visibility = lm.visibility().orElse(1.0f);
                presence = lm.presence().orElse(1.0f);
                landmarks.add(new Landmark(lm.x(), lm.y(), lm.z(), visibility, presence));
            } else {
                // 真的缺點才補零，這種點的可信度本來就該是 0
                landmarks.add(new Landmark(0f, 0f, 0f, 0f, 0f));
            }

            // Step2：同一個點的 world 座標（公尺，原點在髖中心），角度計算會用它
            if (rawWorld != null && id < rawWorld.size()) {
                com.google.mediapipe.tasks.components.containers.Landmark w = rawWorld.get(id);
                worldLandmarks.add(new Landmark(w.x(), w.y(), w.z(), visibility, presence));
            }
        }

        // Step3：建立 PoseFrame（frameIndex 依時間戳記推導）
        return new PoseFrame(
                (int) (timeMs / FRAME_INTERVAL_MS),
                timeMs,
                landmarks,
                worldLandmarks.size() >= PoseLandmark.COUNT ? worldLandmarks : null);
    }

    /**
     * 取出這次偵測結果的第一個人的 world landmark。
     *
     * @param result MediaPipe 偵測結果
     * @return world landmark 列表；沒有時回傳 null
     */
    private static List<com.google.mediapipe.tasks.components.containers.Landmark> worldOf(
            PoseLandmarkerResult result) {
        List<List<com.google.mediapipe.tasks.components.containers.Landmark>> world =
                result.worldLandmarks();
        if (world == null || world.isEmpty()) {
            return null;
        }
        return world.get(0);
    }

    /**
     * 對整組 landmark 做一階低通濾波。
     *
     * <p>MediaPipe 逐幀輸出本來就有抖動，不平滑直接送進馬達會讓機器人抽搐。
     *
     * @param current  這一幀的座標
     * @param previous 上一幀（已平滑）的座標；為 null 或長度不符時直接回傳 current
     * @return 平滑後的座標列表
     */
    private static List<Landmark> smooth(List<Landmark> current, List<Landmark> previous) {
        if (current == null) {
            return null;
        }
        if (previous == null || previous.size() != current.size()) {
            return current;
        }
        List<Landmark> smoothed = new ArrayList<>(current.size());
        for (int i = 0; i < current.size(); i++) {
            smoothed.add(PoseMath.lowPass(current.get(i), previous.get(i), SMOOTHING_ALPHA));
        }
        return smoothed;
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
                        landmark.visibility().orElse(1.0f),
                        landmark.presence().orElse(1.0f)));
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
        boolean leftHandHigh = isVisible(leftWrist) && leftWrist.y() < leftShoulder.y() - 0.05f;
        boolean rightHandHigh = isVisible(rightWrist) && rightWrist.y() < rightShoulder.y() - 0.05f;

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
        if (bodyLean < -0.03f) {
            return "LEAN_LEFT";
        }
        if (bodyLean > 0.03f) {
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
    /**
     * Build a second-based keyframe table from sampled pose features.
     */
    private List<KeyframeEntry> buildKeyframes(List<PoseFeature> features, List<Long> featureTimes,
            long durationMs) {
        List<KeyframeEntry> keyframes = new ArrayList<>();
        if (features == null || featureTimes == null || features.isEmpty()) {
            return keyframes;
        }

        int totalSeconds = Math.max(1, (int) Math.ceil(Math.max(durationMs, 1L) / 1000.0));
        for (int secondIndex = 0; secondIndex < totalSeconds; secondIndex++) {
            long startMs = secondIndex * 1000L;
            long endMs = startMs + 1000L;
            java.util.Map<String, Integer> counts = new java.util.LinkedHashMap<>();
            java.util.Map<String, Float> amplitudeTotals = new java.util.LinkedHashMap<>();
            int sampleCount = 0;

            for (int i = 0; i < features.size() && i < featureTimes.size(); i++) {
                long timestampMs = featureTimes.get(i);
                if (timestampMs < startMs || timestampMs >= endMs) {
                    continue;
                }
                PoseFeature feature = features.get(i);
                // 遮擋幀不列入統計：MediaPipe 在看不到人的時候仍會「猜」出座標，
                // 拿去投票會製造出根本沒發生過的動作。
                if (feature != null && !feature.confident) {
                    continue;
                }
                String eventType = resolveKeyframeEvent(feature);
                float amplitudePercent = estimateKeyframeAmplitude(feature, eventType);
                counts.put(eventType, counts.getOrDefault(eventType, 0) + 1);
                amplitudeTotals.put(eventType,
                        amplitudeTotals.getOrDefault(eventType, 0f) + amplitudePercent);
                sampleCount++;
            }

            String bestEvent = "NEUTRAL";
            float bestScore = -1f;
            float bestAmplitude = 0f;
            boolean hasNonNeutral = false;
            for (java.util.Map.Entry<String, Integer> entry : counts.entrySet()) {
                String eventType = entry.getKey();
                if (!"NEUTRAL".equals(eventType)) {
                    hasNonNeutral = true;
                }
            }
            for (java.util.Map.Entry<String, Integer> entry : counts.entrySet()) {
                String eventType = entry.getKey();
                if (hasNonNeutral && "NEUTRAL".equals(eventType)) {
                    continue;
                }
                int count = entry.getValue();
                float totalAmplitude = amplitudeTotals.getOrDefault(eventType, 0f);
                float score = (count * 1000f) + (totalAmplitude * 10f);
                if (score > bestScore) {
                    bestScore = score;
                    bestEvent = eventType;
                    bestAmplitude = count == 0 ? 0f : totalAmplitude / count;
                }
            }

            if (sampleCount == 0) {
                bestEvent = "NEUTRAL";
                bestAmplitude = 0f;
            }

            // robotMotion 留空，交給播放時的 MotionPoseMap 決定。
            // 舊版這裡填的是 bestEvent（例如 "BOTH_HANDS_UP"）而不是 motion 名稱，
            // 導致 MainActivity 裡「已解析為實際 motion 名就直接用」那條分支永遠走不到，
            // CSV 也白白多出一個和 event_type 完全相同的欄位。
            keyframes.add(new KeyframeEntry(
                    secondIndex,
                    startMs,
                    bestEvent,
                    null,
                    clampPercent(bestAmplitude),
                    sampleCount));
        }

        return keyframes;
    }

    /**
     * Write the keyframe table as CSV.
     */
    private void writeKeyframes(FileWriter writer, List<KeyframeEntry> keyframes) throws Exception {
        writer.write("second_index,time_ms,event_type,robot_motion,amplitude_percent,sample_count\n");
        for (KeyframeEntry keyframe : keyframes) {
            writer.write(String.format(
                    Locale.US,
                    "%d,%d,%s,%s,%.1f,%d\n",
                    keyframe.secondIndex,
                    keyframe.timeMs,
                    keyframe.eventType,
                    keyframe.robotMotion == null ? "" : keyframe.robotMotion,
                    keyframe.amplitudePercent,
                    keyframe.sampleCount));
        }
    }

    /**
     * Resolve a stable keyframe event from one PoseFeature.
     */
    private String resolveKeyframeEvent(PoseFeature feature) {
        if (feature == null) {
            return "NEUTRAL";
        }
        String bestEvent = "NEUTRAL";
        float bestScore = 0f;

        float leftScore = scoreLeftHandEvent(feature);
        float rightScore = scoreRightHandEvent(feature);
        float bothScore = scoreBothHandsEvent(feature, leftScore, rightScore);
        float leanLeftScore = scoreLeanLeftEvent(feature);
        float leanRightScore = scoreLeanRightEvent(feature);

        if (bothScore > bestScore) {
            bestScore = bothScore;
            bestEvent = "BOTH_HANDS_UP";
        }
        if (leftScore > bestScore) {
            bestScore = leftScore;
            bestEvent = "LEFT_HAND_UP";
        }
        if (rightScore > bestScore) {
            bestScore = rightScore;
            bestEvent = "RIGHT_HAND_UP";
        }
        if (leanLeftScore > bestScore) {
            bestScore = leanLeftScore;
            bestEvent = "LEAN_LEFT";
        }
        if (leanRightScore > bestScore) {
            bestScore = leanRightScore;
            bestEvent = "LEAN_RIGHT";
        }

        if (feature.inferredEventType != null && !feature.inferredEventType.isEmpty()) {
            float inferredScore = scoreEventName(feature, feature.inferredEventType);
            if (inferredScore >= bestScore) {
                bestScore = inferredScore;
                bestEvent = feature.inferredEventType;
            }
        }

        return bestScore >= 0.35f ? bestEvent : "NEUTRAL";
    }

    /**
     * Estimate keyframe amplitude in percent.
     */
    private float estimateKeyframeAmplitude(PoseFeature feature, String eventType) {
        if (feature == null || eventType == null) {
            return 0f;
        }
        switch (eventType) {
            case "BOTH_HANDS_UP":
                return clampPercent(scoreBothHandsEvent(feature, scoreLeftHandEvent(feature),
                        scoreRightHandEvent(feature)) * 100f);
            case "LEFT_HAND_UP":
                return clampPercent(scoreLeftHandEvent(feature) * 100f);
            case "RIGHT_HAND_UP":
                return clampPercent(scoreRightHandEvent(feature) * 100f);
            case "LEAN_LEFT":
            case "LEAN_RIGHT":
                return clampPercent(Math.max(Math.abs(feature.bodyLean) * 1400f,
                        Math.abs(feature.torsoLean) * 3.0f));
            case "NEUTRAL":
                return 0f;
            default:
                return clampPercent(Math.max(scoreLeftHandEvent(feature), scoreRightHandEvent(feature)) * 100f);
        }
    }

    /**
     * Score how strongly the feature matches a left-hand-up motion.
     */
    private float scoreLeftHandEvent(PoseFeature feature) {
        if (feature == null) {
            return 0f;
        }
        float wristBonus = feature.leftWristAboveShoulder ? 1f : 0f;
        float armScore = normalizedArmLiftScore(feature.leftArmAngle);
        return clamp01(Math.max(wristBonus, armScore * 0.95f));
    }

    /**
     * Score how strongly the feature matches a right-hand-up motion.
     */
    private float scoreRightHandEvent(PoseFeature feature) {
        if (feature == null) {
            return 0f;
        }
        float wristBonus = feature.rightWristAboveShoulder ? 1f : 0f;
        float armScore = normalizedArmLiftScore(feature.rightArmAngle);
        return clamp01(Math.max(wristBonus, armScore * 0.95f));
    }

    /**
     * Score how strongly both hands are raised together.
     */
    private float scoreBothHandsEvent(PoseFeature feature, float leftScore, float rightScore) {
        if (feature == null) {
            return 0f;
        }
        float bothWristBonus = feature.leftWristAboveShoulder && feature.rightWristAboveShoulder ? 0.2f : 0f;
        return clamp01(Math.min(leftScore, rightScore) + bothWristBonus);
    }

    /**
     * Score left torso lean.
     */
    private float scoreLeanLeftEvent(PoseFeature feature) {
        if (feature == null) {
            return 0f;
        }
        return clamp01(((-feature.bodyLean) - 0.015f) / 0.06f);
    }

    /**
     * Score right torso lean.
     */
    private float scoreLeanRightEvent(PoseFeature feature) {
        if (feature == null) {
            return 0f;
        }
        return clamp01((feature.bodyLean - 0.015f) / 0.06f);
    }

    /**
     * Score a named event when it comes from the existing analyzer.
     */
    private float scoreEventName(PoseFeature feature, String eventType) {
        if (feature == null || eventType == null) {
            return 0f;
        }
        switch (eventType) {
            case "BOTH_HANDS_UP":
                return scoreBothHandsEvent(feature, scoreLeftHandEvent(feature), scoreRightHandEvent(feature));
            case "LEFT_HAND_UP":
                return scoreLeftHandEvent(feature);
            case "RIGHT_HAND_UP":
                return scoreRightHandEvent(feature);
            case "LEAN_LEFT":
                return scoreLeanLeftEvent(feature);
            case "LEAN_RIGHT":
                return scoreLeanRightEvent(feature);
            case "NEUTRAL":
                return 0f;
            default:
                return 0f;
        }
    }

    /**
     * 把手臂抬升角換算成 0..1 的分數。
     *
     * <p>吃的是 {@link PoseFeature#leftArmAngle}（髖-肩-肘夾角）：
     * 自然下垂約 20 度給 0 分、平舉約 90 度給滿分、高舉過頭同樣滿分。
     *
     * <p>舊版寫成 {@code (170 - armAngle) / 80}，是照「手肘角」的尺度設計的
     * （180 度＝手打直＝放鬆）。但當時 {@code leftArmAngle} 誤植成和手肘角同一個算式，
     * 於是這個分數實際上在量「手肘有沒有彎」——
     * 手垂著只要手肘一彎就被判成舉手，門檻怎麼調都調不準。
     *
     * @param armAngle 手臂抬升角（度）
     * @return 0..1 的抬升分數
     */
    private float normalizedArmLiftScore(float armAngle) {
        if (Float.isNaN(armAngle) || armAngle <= 0f) {
            return 0f;
        }
        return clamp01((armAngle - 25f) / 70f);
    }

    /**
     * Clamp a percent value to 0..100.
     */
    private float clampPercent(float value) {
        return Math.max(0f, Math.min(100f, value));
    }

    /**
     * Clamp a normalized score to 0..1.
     */
    private float clamp01(float value) {
        return Math.max(0f, Math.min(1f, value));
    }

    private boolean isVisible(NormalizedLandmark landmark) {
        return landmark.visibility().orElse(1.0f) >= 0.35f
                && landmark.presence().orElse(1.0f) >= 0.35f;
    }
}
