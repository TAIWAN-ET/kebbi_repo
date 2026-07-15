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
    public float shoulderSlope;
    public float torsoLean;
    public float headYaw;

    public PoseFeature() {
    }

    public String describe() {
        return "L-elbow=" + round(leftElbowAngle)
                + " R-elbow=" + round(rightElbowAngle)
                + " L-arm=" + round(leftArmAngle)
                + " R-arm=" + round(rightArmAngle)
                + " shoulderSlope=" + round(shoulderSlope)
                + " torsoLean=" + round(torsoLean)
                + " headYaw=" + round(headYaw);
    }

    private static float round(float value) {
        return Math.round(value * 10f) / 10f;
    }
}
