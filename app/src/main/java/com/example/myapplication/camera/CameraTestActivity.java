package com.example.myapplication.camera;

import android.Manifest;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.util.Log;
import android.view.TextureView;
import android.widget.Button;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.example.myapplication.R;
import com.example.myapplication.analysis.PoseAnalyzer;
import com.example.myapplication.model.Landmark;
import com.example.myapplication.model.PoseFeature;
import com.example.myapplication.model.PoseFrame;
import com.example.myapplication.model.PoseLandmark;
import com.example.myapplication.model.RobotCommand;
import com.example.myapplication.robot.RobotMapper;
import com.google.mediapipe.framework.image.BitmapImageBuilder;
import com.google.mediapipe.framework.image.MPImage;
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark;
import com.google.mediapipe.tasks.core.BaseOptions;
import com.google.mediapipe.tasks.vision.core.RunningMode;
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarker;
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarkerResult;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * P4 基礎煙霧測試（smoke test）：只確認
 *   ✅ Camera 能開
 *   ✅ Preview 正常
 *   ✅ MediaPipe 能收到 frame
 *   ✅ Log / UI 持續輸出 frame / timestamp
 *   ✅ PoseFeature → RobotMapper → RobotCommand（先只印出，之後開 execute）
 *
 * <p>用途：驗證 Camera + MediaPipe 串流管道是否通順，並把每幀特徵透過
 * {@link RobotMapper} 轉成機器人指令印在 Log（先不實際控制機器人）。
 *
 * <p>誰會呼叫它：
 * Android 系統在使用者點擊 Camera Test 按鈕時啟動此 Activity。
 * 使用者手動觸發。
 *
 * <p>不負責什麼：
 * 不控制機器人（只印 RobotCommand），不處理 CSV/JSON 序列化，
 * 不做 round-trip 驗證。Camera2 開關與預覽由 {@link CameraController} 負責。
 * 純粹是煙霧測試。
 */
public class CameraTestActivity extends AppCompatActivity {
    private static final String TAG = "CameraTest";
    private static final String POSE_MODEL = "pose_landmarker_lite.task";
    private static final int CAMERA_PERMISSION = 1001;
    private static final long FRAME_INTERVAL_MS = 200L;

    private TextureView previewTexture;
    private TextView statusText;
    private CameraController cameraController;
    private PoseLandmarker poseLandmarker;
    private final RobotMapper robotMapper = new RobotMapper();
    private final AtomicLong frameIndex = new AtomicLong(0);
    private final AtomicLong detectedFrames = new AtomicLong(0);
    private long lastUiUpdate = 0;

    // CameraController 狀態回呼：把訊息顯示到 statusText
    private final CameraController.StatusListener statusListener = message ->
            runOnUiThread(() -> statusText.setText(message));

    // 預覽就緒後開始取幀迴圈
    private final CameraController.PreviewReadyListener previewReadyListener =
            this::runFrameLoop;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_camera_test);
        previewTexture = findViewById(R.id.previewTexture);
        statusText = findViewById(R.id.cameraStatus);
        Button back = findViewById(R.id.backButton);
        back.setOnClickListener(v -> finish());

        setupPoseLandmarker();
        cameraController = new CameraController(previewTexture, previewReadyListener, statusListener);

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED) {
            cameraController.start();
        } else {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.CAMERA}, CAMERA_PERMISSION);
        }
    }

    /**
     * 初始化 MediaPipe Pose Landmarker（LIVE_STREAM 模式）。
     *
     * <p>官方文件對三種模式的分工很明確：單張用 IMAGE、已錄好的影片用 VIDEO、
     * <b>即時串流用 LIVE_STREAM 搭配非同步的 {@code detectAsync}</b>。
     * 舊版對著即時相機用 VIDEO 模式的同步 {@code detectForVideo}，
     * 會在相機的 Handler 執行緒上阻塞等待推論結果，拖慢預覽。
     *
     * <p>信心度三個參數也一併補齊，和 {@link com.example.myapplication.analysis.VideoAnalyzer}
     * 保持一致（雖然預設值同樣是 0.5，但寫出來才不會讓人以為兩邊設定不同）。
     */
    private void setupPoseLandmarker() {
        try {
            BaseOptions baseOptions = BaseOptions.builder().setModelAssetPath(POSE_MODEL).build();
            PoseLandmarker.PoseLandmarkerOptions options = PoseLandmarker.PoseLandmarkerOptions.builder()
                    .setBaseOptions(baseOptions)
                    .setRunningMode(RunningMode.LIVE_STREAM)
                    .setNumPoses(1)
                    .setMinPoseDetectionConfidence(0.5f)
                    .setMinPosePresenceConfidence(0.5f)
                    .setMinTrackingConfidence(0.5f)
                    .setResultListener(this::onPoseResult)
                    .setErrorListener(error -> Log.e(TAG, "PoseLandmarker error", error))
                    .build();
            poseLandmarker = PoseLandmarker.createFromOptions(this, options);
        } catch (Exception e) {
            statusText.setText("PoseLandmarker init failed: " + e.getMessage());
        }
    }

    /**
     * LIVE_STREAM 模式的偵測結果回呼（由 MediaPipe 在自己的執行緒上呼叫）。
     *
     * @param result MediaPipe 偵測結果
     * @param input  當初送進去的影像
     */
    private void onPoseResult(PoseLandmarkerResult result, MPImage input) {
        detectedFrames.incrementAndGet();
        if (result.landmarks().isEmpty()) {
            return;
        }

        List<NormalizedLandmark> raw = result.landmarks().get(0);
        PoseFrame frame = toPoseFrame(result.timestampMs(), raw, worldOf(result));
        PoseFeature feature = PoseAnalyzer.analyzeFrame(frame);
        Log.i(TAG, feature.describe());

        // 遮擋幀不轉指令：MediaPipe 看不到人的時候仍會猜座標，送出去就是假動作
        if (!feature.confident) {
            return;
        }

        // PoseFeature → RobotCommand（先只印出，之後開 execute）
        List<RobotCommand> commands = robotMapper.map(feature);
        for (RobotCommand cmd : commands) {
            Log.i(TAG, "Cmd " + cmd.describe());
        }
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

    private void runFrameLoop() {
        cameraController.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (isFinishing()) {
                    return;
                }
                if (cameraController.isCameraOpen() && poseLandmarker != null) {
                    Bitmap bmp = cameraController.grabPreview();
                    if (bmp != null) {
                        long idx = frameIndex.incrementAndGet();
                        try {
                            MPImage image = new BitmapImageBuilder(bmp).build();
                            // 非同步送出，結果會進 onPoseResult。
                            // 時間戳用 idx 遞增，符合 LIVE_STREAM 對單調遞增的要求。
                            poseLandmarker.detectAsync(image, idx * FRAME_INTERVAL_MS);
                        } catch (Exception ignored) {
                            // 煙霧測試：單幀錯誤不中斷迴圈
                        }
                        // 這裡不能 recycle()：detectAsync 送出後 MediaPipe 還在用這張 Bitmap，
                        // 提早回收會讓推論執行緒踩到已釋放的記憶體。交給 GC 處理。

                        long now = System.currentTimeMillis();
                        if (now - lastUiUpdate > 500) {
                            lastUiUpdate = now;
                            final long f = frameIndex.get();
                            final long d = detectedFrames.get();
                            Log.i(TAG, "frames=" + f + " mediapipe=" + d + " ts=" + now);
                            statusListener.onStatus(
                                    "Camera OK\nframes: " + f + "\nMediaPipe received: " + d);
                        }
                    }
                }
                cameraController.postDelayed(this, FRAME_INTERVAL_MS);
            }
        }, FRAME_INTERVAL_MS);
    }

    /**
     * 把 MediaPipe 的 NormalizedLandmark 列表轉成專案內部的 PoseFrame。
     *
     * @param timeMs 影片時間戳記（毫秒）
     * @param raw    MediaPipe 偵測到的 NormalizedLandmark 列表
     * @return 轉換後的 PoseFrame
     */
    private PoseFrame toPoseFrame(long timeMs, List<NormalizedLandmark> raw,
            List<com.google.mediapipe.tasks.components.containers.Landmark> rawWorld) {
        List<Landmark> landmarks = new ArrayList<>();
        List<Landmark> worldLandmarks = new ArrayList<>();
        for (int id = 0; id < PoseLandmark.COUNT; id++) {
            float visibility = 0f;
            float presence = 0f;
            if (id < raw.size()) {
                NormalizedLandmark lm = raw.get(id);
                // Optional 沒被填時當成「正常」，不是 0，否則可信度檢查會全滅
                visibility = lm.visibility().orElse(1.0f);
                presence = lm.presence().orElse(1.0f);
                landmarks.add(new Landmark(lm.x(), lm.y(), lm.z(), visibility, presence));
            } else {
                landmarks.add(new Landmark(0f, 0f, 0f, 0f, 0f));
            }
            // world 座標（公尺）供角度計算使用
            if (rawWorld != null && id < rawWorld.size()) {
                com.google.mediapipe.tasks.components.containers.Landmark w = rawWorld.get(id);
                worldLandmarks.add(new Landmark(w.x(), w.y(), w.z(), visibility, presence));
            }
        }
        return new PoseFrame(
                (int) (timeMs / FRAME_INTERVAL_MS),
                timeMs,
                landmarks,
                worldLandmarks.size() >= PoseLandmark.COUNT ? worldLandmarks : null);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode,
                                            @NonNull String[] permissions,
                                            @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == CAMERA_PERMISSION && grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            cameraController.start();
        } else {
            Log.e(TAG, "Camera permission denied");
            statusText.setText("Camera permission denied");
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (cameraController != null) {
            cameraController.close();
        }
        if (poseLandmarker != null) {
            poseLandmarker.close();
        }
    }
}
