package com.example.myapplication;

/**
 * 動作序列的來源。
 *
 * <p>用途：標示一個 {@link MotionSequence} 是從影片、攝影機還是 JSON 檔案載入。
 *
 * <p>誰會呼叫它：
 * {@link PoseIO#readCsv(File)} 設定為 VIDEO，
 * {@link PoseIO#readJson(File)} 設定為 JSON，
 * {@link MainActivity#analyzeBuiltInClip()} 設定為 VIDEO，
 * {@link MainActivity#analyzeDanceVideo(android.net.Uri)} 設定為 VIDEO，
 * {@link CameraTestActivity} 設定為 CAMERA。
 *
 * <p>不負責什麼：
 * 不處理任何檔案 I/O，不解析任何資料，不依賴 Android 或 MediaPipe。
 */
public enum MotionSource {
    /** 動作序列來自影片檔案。 */
    VIDEO,
    /** 動作序列來自即時攝影機畫面。 */
    CAMERA,
    /** 動作序列來自 JSON 檔案。 */
    JSON
}
