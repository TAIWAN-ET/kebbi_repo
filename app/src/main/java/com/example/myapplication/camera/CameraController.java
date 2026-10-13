package com.example.myapplication.camera;

import android.graphics.Bitmap;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;
import android.view.Surface;
import android.view.TextureView;

import androidx.annotation.NonNull;

import java.util.Collections;

/**
 * Camera2 開關與預覽封裝。
 *
 * <p>用途：把 Camera2 的 open / close / startPreview / stopPreview /
 * TextureView surface 生命週期、預覽 HandlerThread 全部集中在這裡，
 * 讓 {@link CameraTestActivity}（或未來的 Camera 即時模式）只負責 UI、
 * MediaPipe 與 PoseAnalyzer。
 *
 * <p>誰會呼叫它：
 * {@link CameraTestActivity} 在權限取得後呼叫 {@link #start()}，
 * 畫面前導列就緒時透過 {@link PreviewReadyListener} 通知呼叫端開始取幀，
 * 關閉時呼叫 {@link #close()}。
 *
 * <p>不負責什麼：
 * 不做 MediaPipe 偵測、不做 PoseAnalyzer、不做權限請求（由 Activity 處理）、
 * 不知道任何人體姿勢資料。
 */
public class CameraController {

    /**
     * 狀態更新回呼，供 UI 顯示進度／錯誤。
     */
    public interface StatusListener {
        void onStatus(String message);
    }

    /**
     * 預覽 Session 設定完成回呼，呼叫端在此開始取幀迴圈。
     */
    public interface PreviewReadyListener {
        void onPreviewReady();
    }

    private static final String TAG = "CameraController";
    private static final int PREVIEW_W = 640;
    private static final int PREVIEW_H = 480;

    private final PreviewReadyListener previewReadyListener;
    private final StatusListener statusListener;
    private TextureView previewTexture;
    private CameraDevice cameraDevice;
    private CameraCaptureSession captureSession;
    private HandlerThread cameraThread;
    private Handler cameraHandler;

    /**
     * @param previewTexture      預覽用的 TextureView
     * @param previewReadyListener 預覽就緒回呼
     * @param statusListener      狀態/錯誤回呼
     */
    public CameraController(TextureView previewTexture,
            PreviewReadyListener previewReadyListener,
            StatusListener statusListener) {
        this.previewTexture = previewTexture;
        this.previewReadyListener = previewReadyListener;
        this.statusListener = statusListener;
    }

    /**
     * 啟動 CameraController：建立預覽執行緒並註冊 TextureView surface 監聽。
     *
     * <p>必須在 CAMERA 權限已取得後呼叫；重複呼叫會被忽略。
     */
    public void start() {
        if (cameraHandler != null) {
            return;
        }
        cameraThread = new HandlerThread("CameraController");
        cameraThread.start();
        cameraHandler = new Handler(cameraThread.getLooper());
        previewTexture.setSurfaceTextureListener(surfaceTextureListener);
    }

    /**
     * 在預覽執行緒上延遲執行任務（供取幀迴圈使用）。
     *
     * @param r       要執行的任務
     * @param delayMs 延遲毫秒
     */
    public void postDelayed(Runnable r, long delayMs) {
        if (cameraHandler != null) {
            cameraHandler.postDelayed(r, delayMs);
        }
    }

    /**
     * 相機是否已開啟。
     *
     * @return 已開啟回傳 true
     */
    public boolean isCameraOpen() {
        return cameraDevice != null;
    }

    /**
     * 抓取目前 TextureView 的畫面（供 MediaPipe 偵測）。
     *
     * @return 目前畫面 Bitmap；TextureView 尚未有內容時為 null
     */
    public Bitmap grabPreview() {
        return previewTexture != null ? previewTexture.getBitmap() : null;
    }

    /**
     * 關閉相機並結束預覽執行緒。
     */
    public void close() {
        closeCamera();
        if (cameraThread != null) {
            cameraThread.quitSafely();
            cameraThread = null;
            cameraHandler = null;
        }
    }

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
            statusListener.onStatus("Camera error: " + error);
            closeCamera();
        }
    };

    /**
     * 開啟相機並設定預覽緩衝區大小。
     */
    private void openCamera() {
        CameraManager manager = (CameraManager) previewTexture.getContext()
                .getSystemService(CameraManager.class.getName());
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
            statusListener.onStatus("openCamera failed: " + e.getMessage());
        }
    }

    /**
     * 建立預覽 Capture Session 並開始重複請求。
     */
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
                                previewReadyListener.onPreviewReady();
                            } catch (CameraAccessException e) {
                                statusListener.onStatus("preview failed: " + e.getMessage());
                            }
                        }

                        @Override
                        public void onConfigureFailed(@NonNull CameraCaptureSession session) {
                            statusListener.onStatus("preview configure failed");
                        }
                    }, cameraHandler);
        } catch (CameraAccessException e) {
            statusListener.onStatus("createCaptureSession failed: " + e.getMessage());
        }
    }

    /**
     * 關閉預覽 Session 與相機。
     */
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
}
