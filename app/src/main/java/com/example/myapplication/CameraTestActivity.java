package com.example.myapplication;

import android.Manifest;
import android.content.pm.PackageManager;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;
import android.util.Size;
import android.view.Surface;
import android.view.TextureView;
import android.widget.Button;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.google.mediapipe.framework.image.BitmapImageBuilder;
import com.google.mediapipe.framework.image.MPImage;
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark;
import com.google.mediapipe.tasks.core.BaseOptions;
import com.google.mediapipe.tasks.vision.core.RunningMode;
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarker;
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarkerResult;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * P4 基礎煙霧測試（smoke test）：只確認
 *   ✅ Camera 能開
 *   ✅ Preview 正常
 *   ✅ MediaPipe 能收到 frame
 *   ✅ Log / UI 持續輸出 frame / timestamp
 * 不做任何姿態辨識或機器人控制。
 *
 * <p>用途：驗證 Camera + MediaPipe 串流管道是否通順，不做任何姿態辨識或機器人控制。
 * 本檔案與 activity_camera_test.xml 為「可整組刪除」的測試程式，
 * 不影響核心 Data Layer（Landmark / PoseFrame / MotionSequence / PoseIO / PosePipeline）。
 *
 * <p>誰會呼叫它：
 * Android 系統在使用者點擊 Camera Test 按鈕時啟動此 Activity。
 * 使用者手動觸發。
 *
 * <p>不負責什麼：
 * 不做姿態辨識結果的分析，不控制機器人，不處理 CSV/JSON 序列化，
 * 不做 round-trip 驗驗證。純粹是煙霧測試。
 */
public class CameraTestActivity extends AppCompatActivity {
    private static final String TAG = "CameraTest";
    private static final String POSE_MODEL = "pose_landmarker_lite.task";
    private static final int CAMERA_PERMISSION = 1001;
    private static final long FRAME_INTERVAL_MS = 200L;
    private static final int PREVIEW_W = 640;
    private static final int PREVIEW_H = 480;

    private TextureView previewTexture;
    private TextView statusText;
    private CameraDevice cameraDevice;
    private CameraCaptureSession captureSession;
    private PoseLandmarker poseLandmarker;
    private HandlerThread cameraThread;
    private Handler cameraHandler;
    private final AtomicLong frameIndex = new AtomicLong(0);
    private final AtomicLong detectedFrames = new AtomicLong(0);
    private long lastUiUpdate = 0;

    private final TextureView.SurfaceTextureListener surfaceTextureListener =
            new TextureView.SurfaceTextureListener() {
                @Override
                public void onSurfaceTextureAvailable(SurfaceTexture st, int w, int h) {
                    openCamera();
                }

                @Override
                public void onSurfaceTextureSizeChanged(SurfaceTexture st, int w, int h) {
                }

                @Override
                public boolean onSurfaceTextureDestroyed(SurfaceTexture st) {
                    return true;
                }

                @Override
                public void onSurfaceTextureUpdated(SurfaceTexture st) {
                }
            };

    private final CameraDevice.StateCallback stateCallback = new CameraDevice.StateCallback() {
        @Override
        public void onOpened(@NonNull CameraDevice camera) {
            cameraDevice = camera;
            Log.i(TAG, "Camera opened");
            startPreview();
        }

        @Override
        public void onDisconnected(@NonNull CameraDevice camera) {
            Log.i(TAG, "Camera disconnected");
            closeCamera();
        }

        @Override
        public void onError(@NonNull CameraDevice camera, int error) {
            Log.e(TAG, "Camera error: " + error);
            runOnUiThread(() -> statusText.setText("Camera error: " + error));
            closeCamera();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_camera_test);
        previewTexture = findViewById(R.id.previewTexture);
        statusText = findViewById(R.id.cameraStatus);
        Button back = findViewById(R.id.backButton);
        back.setOnClickListener(v -> finish());

        setupPoseLandmarker();
        cameraThread = new HandlerThread("CameraTest");
        cameraThread.start();
        cameraHandler = new Handler(cameraThread.getLooper());

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED) {
            previewTexture.setSurfaceTextureListener(surfaceTextureListener);
        } else {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.CAMERA}, CAMERA_PERMISSION);
        }
    }

    private void setupPoseLandmarker() {
        try {
            BaseOptions baseOptions = BaseOptions.builder().setModelAssetPath(POSE_MODEL).build();
            PoseLandmarker.PoseLandmarkerOptions options = PoseLandmarker.PoseLandmarkerOptions.builder()
                    .setBaseOptions(baseOptions)
                    .setRunningMode(RunningMode.VIDEO)
                    .setNumPoses(1)
                    .build();
            poseLandmarker = PoseLandmarker.createFromOptions(this, options);
        } catch (Exception e) {
            statusText.setText("PoseLandmarker init failed: " + e.getMessage());
        }
    }

    private void openCamera() {
        CameraManager manager = (CameraManager) getSystemService(CAMERA_SERVICE);
        try {
            String cameraId = manager.getCameraIdList()[0];
            SurfaceTexture st = previewTexture.getSurfaceTexture();
            if (st == null) {
                return;
            }
            st.setDefaultBufferSize(PREVIEW_W, PREVIEW_H);
            manager.openCamera(cameraId, stateCallback, cameraHandler);
        } catch (CameraAccessException | SecurityException e) {
            Log.e(TAG, "openCamera failed: " + e.getMessage());
            statusText.setText("openCamera failed: " + e.getMessage());
        }
    }

    private void startPreview() {
        try {
            SurfaceTexture st = previewTexture.getSurfaceTexture();
            if (st == null) {
                return;
            }
            Surface surface = new Surface(st);
            cameraDevice.createCaptureSession(Collections.singletonList(surface),
                    new CameraCaptureSession.StateCallback() {
                        @Override
                        public void onConfigured(@NonNull CameraCaptureSession session) {
                            captureSession = session;
                            try {
                                CaptureRequest.Builder builder =
                                        cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
                                builder.addTarget(surface);
                                session.setRepeatingRequest(builder.build(), null, cameraHandler);
                                runFrameLoop();
                            } catch (CameraAccessException e) {
                                statusText.setText("preview failed: " + e.getMessage());
                            }
                        }

                        @Override
                        public void onConfigureFailed(@NonNull CameraCaptureSession session) {
                            statusText.setText("preview configure failed");
                        }
                    }, cameraHandler);
        } catch (CameraAccessException e) {
            statusText.setText("createCaptureSession failed: " + e.getMessage());
        }
    }

    private void runFrameLoop() {
        cameraHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (isFinishing()) {
                    return;
                }
                if (cameraDevice != null && poseLandmarker != null) {
                    android.graphics.Bitmap bmp = previewTexture.getBitmap();
                    if (bmp != null) {
                        long idx = frameIndex.incrementAndGet();
                        try {
                            MPImage image = new BitmapImageBuilder(bmp).build();
                            PoseLandmarkerResult result = poseLandmarker.detectForVideo(image, idx * FRAME_INTERVAL_MS);
                            detectedFrames.incrementAndGet();
                            if (!result.landmarks().isEmpty()) {
                                List<NormalizedLandmark> raw = result.landmarks().get(0);
                                PoseFrame frame = toPoseFrame(idx * FRAME_INTERVAL_MS, raw);
                                PoseFeature feature = PoseAnalyzer.analyzeFrame(frame);
                                Log.i(TAG, feature.describe());
                            }
                        } catch (Exception ignored) {
                            // 煙霧測試：單幀錯誤不中斷迴圈
                        }
                        bmp.recycle();

                        long now = System.currentTimeMillis();
                        if (now - lastUiUpdate > 500) {
                            lastUiUpdate = now;
                            final long f = frameIndex.get();
                            final long d = detectedFrames.get();
                            Log.i(TAG, "frames=" + f + " mediapipe=" + d + " ts=" + now);
                            runOnUiThread(() -> statusText.setText(
                                    "Camera OK\nframes: " + f + "\nMediaPipe received: " + d));
                        }
                    }
                }
                cameraHandler.postDelayed(this, FRAME_INTERVAL_MS);
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

    @Override
    public void onRequestPermissionsResult(int requestCode,
                                            @NonNull String[] permissions,
                                            @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == CAMERA_PERMISSION && grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            previewTexture.setSurfaceTextureListener(surfaceTextureListener);
        } else {
            Log.e(TAG, "Camera permission denied");
            statusText.setText("Camera permission denied");
        }
    }

    private void closeCamera() {
        if (captureSession != null) {
            captureSession.close();
            captureSession = null;
        }
        if (cameraDevice != null) {
            cameraDevice.close();
            cameraDevice = null;
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        closeCamera();
        if (poseLandmarker != null) {
            poseLandmarker.close();
        }
        if (cameraThread != null) {
            cameraThread.quitSafely();
        }
    }
}
