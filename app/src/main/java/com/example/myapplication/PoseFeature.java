package com.example.myapplication;

/**
 * 人體動作特徵。是「人體資訊」而不是「Robot 指令」。
 *
 * RobotMapper 之後只讀這層，不直接讀 33 個 Landmark；
 * 評分系統（MotionComparator）也直接吃這層。
 * 換成 OpenPose / MoveNet / BlazePose 只要仍能產出 PoseFrame，這層完全不用改。
 *
 * 純 Java POJO。角度單位為度。
 */
public class PoseFeature {
    public float leftElbowAngle;
    public float rightElbowAngle;
    public float leftArmAngle;
    public float rightArmAngle;
    public float leftHipAngle;
    public float rightHipAngle;
    public float shoulderSlope;
    public float torsoLean;
    public float headYaw;

    // 手是否高於肩膀（對應 LEFT/RIGHT_HAND_UP 事件，校正用）
    public boolean leftWristAboveShoulder;
    public boolean rightWristAboveShoulder;
    // 軀幹左右傾斜量（正=右傾，負=左傾，對應 LEAN_LEFT/RIGHT 事件）
    public float bodyLean;
    // 由數值推導出的「姿勢事件類型」，與 MainActivity.detectDanceEvent 同源
    public String inferredEventType = "";

    public PoseFeature() {
    }

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

    private static float round(float value) {
        return Math.round(value * 10f) / 1f / 10f;
    }
}
