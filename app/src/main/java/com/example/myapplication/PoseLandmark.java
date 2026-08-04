package com.example.myapplication;

/**
 * MediaPipe Pose Landmarker 的 33 個 landmark 索引常數，消滅魔術數字。
 *
 * <p>用途：提供 MediaPipe Pose Landmarker 輸出的 33 個關節點編號對應名稱，
 * 讓 {@link PoseAnalyzer}、{@link PoseMath}、{@link MainActivity} 等程式碼
 * 用 {@code PoseLandmark.LEFT_SHOULDER} 取代 {@code 11}，提高可讀性。
 *
 * <p>誰會呼叫它：
 * {@link PoseAnalyzer#analyzeFrame(PoseFrame)} 取關節點時使用，
 * {@link MainActivity#toPoseFrame(long, java.util.List)} 轉換 MediaPipe 輸出時使用，
 * {@link MainActivity#detectDanceEvent(java.util.List)} 偵測舞蹈事件時使用。
 *
 * <p>不負責什麼：
 * 不儲存任何 landmark 座標值，不計算任何角度或距離，不依賴 Android 或 MediaPipe 執行期。
 *
 * <p>參考 MediaPipe 官方 Pose Landmark 編號：
 * https://developers.google.com/mediapipe/solutions/vision/pose_landmarker
 */
public final class PoseLandmark {
    private PoseLandmark() {
    }

    /** 鼻子（Nose）。 */
    public static final int NOSE = 0;
    /** 左眼內側。 */
    public static final int LEFT_EYE_INNER = 1;
    /** 左眼。 */
    public static final int LEFT_EYE = 2;
    /** 左眼外側。 */
    public static final int LEFT_EYE_OUTER = 3;
    /** 右眼內側。 */
    public static final int RIGHT_EYE_INNER = 4;
    /** 右眼。 */
    public static final int RIGHT_EYE = 5;
    /** 右眼外側。 */
    public static final int RIGHT_EYE_OUTER = 6;
    /** 左耳。 */
    public static final int LEFT_EAR = 7;
    /** 右耳。 */
    public static final int RIGHT_EAR = 8;
    /** 嘴部左側。 */
    public static final int MOUTH_LEFT = 9;
    /** 嘴部右側。 */
    public static final int MOUTH_RIGHT = 10;
    /** 左肩。 */
    public static final int LEFT_SHOULDER = 11;
    /** 右肩。 */
    public static final int RIGHT_SHOULDER = 12;
    /** 左肘。 */
    public static final int LEFT_ELBOW = 13;
    /** 右肘。 */
    public static final int RIGHT_ELBOW = 14;
    /** 左腕。 */
    public static final int LEFT_WRIST = 15;
    /** 右腕。 */
    public static final int RIGHT_WRIST = 16;
    /** 左小指。 */
    public static final int LEFT_PINKY = 17;
    /** 右小指。 */
    public static final int RIGHT_PINKY = 18;
    /** 左食指。 */
    public static final int LEFT_INDEX = 19;
    /** 右食指。 */
    public static final int RIGHT_INDEX = 20;
    /** 左拇指。 */
    public static final int LEFT_THUMB = 21;
    /** 右拇指。 */
    public static final int RIGHT_THUMB = 22;
    /** 左髖。 */
    public static final int LEFT_HIP = 23;
    /** 右髖。 */
    public static final int RIGHT_HIP = 24;
    /** 左膝。 */
    public static final int LEFT_KNEE = 25;
    /** 右膝。 */
    public static final int RIGHT_KNEE = 26;
    /** 左踝。 */
    public static final int LEFT_ANKLE = 27;
    /** 右踝。 */
    public static final int RIGHT_ANKLE = 28;
    /** 左腳跟。 */
    public static final int LEFT_HEEL = 29;
    /** 右腳跟。 */
    public static final int RIGHT_HEEL = 30;
    /** 左腳趾。 */
    public static final int LEFT_FOOT_INDEX = 31;
    /** 右腳趾。 */
    public static final int RIGHT_FOOT_INDEX = 32;
    /** Landmark 總數（33）。 */
    public static final int COUNT = 33;
}
