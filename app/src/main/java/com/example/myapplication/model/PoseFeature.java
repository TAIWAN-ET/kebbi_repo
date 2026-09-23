package com.example.myapplication.model;

import com.example.myapplication.MainActivity;
import com.example.myapplication.analysis.PoseAnalyzer;
import com.example.myapplication.math.PoseMath;
import com.example.myapplication.robot.MotionPoseMap;
/**
 * 人體動作特徵。
 *
 * <p>用途：將 {@link PoseFrame} 中的 33 個原始 Landmark 轉換成人體可理解的动作特徵
 * （角度、傾斜、手是否舉高等）。這是「人體資訊」而不是「Robot 指令」。
 *
 * <p>誰會呼叫它：
 * {@link PoseAnalyzer#analyzeFrame(PoseFrame)} 每幀分析時產出，
 * {@link MainActivity} 逐幀計算特徵時使用，
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
    /**
     * 左手臂「抬升角」（度）：髖 → 肩 → 肘 的夾角。
     *
     * <p>手自然下垂約 15~25 度、平舉約 90 度、高舉過頭約 170 度。
     * 舊版這個欄位誤植成和 {@link #leftElbowAngle} 完全相同的算式
     * （肩 → 肘 → 腕），導致肩膀和手肘永遠同步、而且「舉手」判斷實際上在量「手肘有沒有彎」。
     */
    public float leftArmAngle;
    /** 右手臂抬升角（度）：髖 → 肩 → 肘。語意同 {@link #leftArmAngle}。 */
    public float rightArmAngle;
    /** 左髖角度（度）：肩 → 髖 → 膝。 */
    public float leftHipAngle;
    /** 右髖角度（度）：肩 → 髖 → 膝。 */
    public float rightHipAngle;
    /** 肩膀連線傾斜角（度，正值代表右肩高於左肩）。 */
    public float shoulderSlope;
    /**
     * 軀幹「左右側傾」角度（度，正=上身往畫面右側倒）。
     *
     * <p>注意這是<b>側傾</b>不是前傾。舊版 RobotMapper 把它當成頭部俯仰（pitch）在用，
     * 等於「人往旁邊倒 → 機器人點頭」，語意不符，現已改用 {@link #headPitch}。
     */
    public float torsoLean;
    /** 頭部左右偏轉角度（度，正=往畫面右側轉）。 */
    public float headYaw;
    /**
     * 頭部俯仰角度（度，正=抬頭往上看，負=低頭）。
     *
     * <p>以鼻子相對肩膀中點的垂直偏移量、除以肩寬換算而得，是單鏡頭下的合理近似。
     */
    public float headPitch;

    /** 左手腕是否高於肩膀（對應 LEFT_HAND_UP 事件，校正用）。 */
    public boolean leftWristAboveShoulder;
    /** 右手腕是否高於肩膀（對應 RIGHT_HAND_UP 事件，校正用）。 */
    public boolean rightWristAboveShoulder;
    /** 軀幹左右傾斜量（正=右傾，負=左傾，對應 LEAN_LEFT/RIGHT 事件）。 */
    public float bodyLean;
    /** 由數值推導出的「姿勢事件類型」，與 MainActivity.detectDanceEvent 同源。 */
    public String inferredEventType = "";
    /**
     * 這一幀的上半身關鍵點是否足夠可信（visibility / presence 都過門檻）。
     *
     * <p>MediaPipe 在遮擋時仍然會「猜」出座標，直接拿來算角度會產生假動作。
     * 下游（keyframe 統計、RobotMapper）應該跳過 {@code false} 的幀。
     */
    public boolean confident;

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
                + " headPitch=" + round(headPitch)
                + " L-handUp=" + leftWristAboveShoulder
                + " R-handUp=" + rightWristAboveShoulder
                + " bodyLean=" + round(bodyLean)
                + " event=" + inferredEventType
                + " confident=" + confident;
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
