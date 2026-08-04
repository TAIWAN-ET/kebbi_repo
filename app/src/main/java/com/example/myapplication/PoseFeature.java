package com.example.myapplication;

/**
 * 人體動作特徵。
 *
 * <p>用途：將 {@link PoseFrame} 中的 33 個原始 Landmark 轉換成人體可理解的动作特徵
 * （角度、傾斜、手是否舉高等）。這是「人體資訊」而不是「Robot 指令」。
 *
 * <p>誰會呼叫它：
 * {@link PoseAnalyzer#analyzeFrame(PoseFrame)} 每幀分析時產出，
 * {@link MainActivity#analyzeBuiltInClip()} 和 {@link MainActivity#analyzeDanceVideo(android.net.Uri)}
 * 逐幀計算特徵時使用，
 * {@link MotionPoseMap} 根據特徵推導事件類型時間接使用。
 *
 * <p>不負責什麼：
 * 不計算角度或距離（由 {@link PoseMath} 負責），不處理檔案 I/O，
 * 不依賴 Android 或 MediaPipe 執行期。
 *
 * <p>設計原則：RobotMapper 只讀這層，不直接讀 33 個 Landmark；
 * 換成 OpenPose / MoveNet / BlazePose 只要仍能產出 PoseFrame，這層完全不用改。
 */
public class PoseFeature {
    /** 左肘角度（度）。 */
    public float leftElbowAngle;
    /** 右肘角度（度）。 */
    public float rightElbowAngle;
    /** 左手臂角度（度）。 */
    public float leftArmAngle;
    /** 右手臂角度（度）。 */
    public float rightArmAngle;
    /** 左髋角度（度）。 */
    public float leftHipAngle;
    /** 右髋角度（度）。 */
    public float rightHipAngle;
    /** 肩膀連線傾斜角（度，正值代表右肩高於左肩）。 */
    public float shoulderSlope;
    /** 軀幹傾斜角度（度）。 */
    public float torsoLean;
    /** 头部偏轉角度（度）。 */
    public float headYaw;

    /** 左手腕是否高於肩膀（對應 LEFT_HAND_UP 事件，校正用）。 */
    public boolean leftWristAboveShoulder;
    /** 右手腕是否高於肩膀（對應 RIGHT_HAND_UP 事件，校正用）。 */
    public boolean rightWristAboveShoulder;
    /** 軀幹左右傾斜量（正=右傾，負=左傾，對應 LEAN_LEFT/RIGHT 事件）。 */
    public float bodyLean;
    /** 由數值推導出的「姿勢事件類型」，與 {@link MainActivity#detectDanceEvent} 同源。 */
    public String inferredEventType = "";

    /** 無參建構子。 */
    public PoseFeature() {
    }

    /**
     * 回傳此人體特徵的可讀描述字串。
     *
     * @return 格式如 "L-elbow=91.8 R-elbow=87.3 L-arm=91.8 R-arm=87.3 ..."
     */
    public String describe() {
        return "L-elbow=" + round(leftElbowAngle)
                + " R-elbow=" + round(rightElbowAngle)
                + " L-arm=" + round(leftArmAngle)
                + " R-arm=" + round(rightArmAngle)
                + " L-hip=" + round(leftHipAngle)
                + " R-hip=" + round(rightHipAngle)
                + " shoulderSlope=" + round(shoulderSlope)
                + " torsoLean=" + round(torsoLean)
                + " headYaw=" + round(headYaw)
                + " L-handUp=" + leftWristAboveShoulder
                + " R-handUp=" + rightWristAboveShoulder
                + " bodyLean=" + round(bodyLean)
                + " event=" + inferredEventType;
    }

    /**
     * 將浮點數保留一位小數。
     *
     * @param value 原始浮點數
     * @return 保留一位小數的浮點數
     */
    private static float round(float value) {
        return Math.round(value * 10f) / 1f / 10f;
    }
}
