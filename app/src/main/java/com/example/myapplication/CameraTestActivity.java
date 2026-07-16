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
import com.google.mediapipe.tasks.core.BaseOptions;
import com.google.mediapipe.tasks.vision.core.RunningMode;
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarker;

import java.util.Collections;
import java.util.concurrent.atomic.AtomicLong;

/**
 * P4 基礎煙霧測試（smoke test）：只確認
 *   ✅ Camera 能開
 *   ✅ Preview 正常
 *   ✅ MediaPipe 能收到 frame
 *   ✅ Log / UI 持續輸出 frame / timestamp
 * 不做任何姿態辨識或機器人控制。
 *
 * 本檔案與 activity_camera_test.xml 為「可整組刪除」的測試程式，
 * 不影響核心 Data Layer（Landmark / PoseFrame / MotionSequence / PoseIO / PosePipeline）。
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
                            poseLandmarker.detectForVideo(image, idx * FRAME_INTERVAL_MS);
                            detectedFrames.incrementAndGet();
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
