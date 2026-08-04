package com.example.myapplication;

import java.util.ArrayList;
import java.util.List;

/**
 * 把 MotionSequence（PoseFrame 序列）轉換成 PoseFeature 序列。
 * 這一層是從「原始座標」邁向「動作理解」的關鍵：輸出人體動作特徵，而非 Robot 指令。
 *
 * 依賴 PoseMath 與 PoseLandmark，完全不知道 MediaPipe 或 Kebbi。
 * 純 Java，不依賴 Android。
 */
public final class PoseAnalyzer {

    private PoseAnalyzer() {
    }

    /**
     * 分析單一影格，產出該影格的人體動作特徵。
     */
    public static PoseFeature analyzeFrame(PoseFrame frame) {
        PoseFeature feature = new PoseFeature();
        if (frame == null || frame.landmarks == null
                || frame.landmarks.size() < PoseLandmark.COUNT) {
            return feature;
        }
        List<Landmark> lm = frame.landmarks;

        Landmark leftShoulder = lm.get(PoseLandmark.LEFT_SHOULDER);
        Landmark rightShoulder = lm.get(PoseLandmark.RIGHT_SHOULDER);
        Landmark leftElbow = lm.get(PoseLandmark.LEFT_ELBOW);
        Landmark rightElbow = lm.get(PoseLandmark.RIGHT_ELBOW);
        Landmark leftWrist = lm.get(PoseLandmark.LEFT_WRIST);
        Landmark rightWrist = lm.get(PoseLandmark.RIGHT_WRIST);
        Landmark leftHip = lm.get(PoseLandmark.LEFT_HIP);
        Landmark rightHip = lm.get(PoseLandmark.RIGHT_HIP);
        Landmark nose = lm.get(PoseLandmark.NOSE);

        feature.leftElbowAngle = PoseMath.calculateAngle(leftShoulder, leftElbow, leftWrist);
        feature.rightElbowAngle = PoseMath.calculateAngle(rightShoulder, rightElbow, rightWrist);
        feature.leftArmAngle = PoseMath.calculateAngle(leftShoulder, leftElbow, leftWrist);
        feature.rightArmAngle = PoseMath.calculateAngle(rightShoulder, rightElbow, rightWrist);
        feature.leftHipAngle = PoseMath.calculateAngle(leftShoulder, leftHip, leftWrist);
        feature.rightHipAngle = PoseMath.calculateAngle(rightShoulder, rightHip, rightWrist);

        feature.shoulderSlope = shoulderSlopeDeg(leftShoulder, rightShoulder);
        feature.torsoLean = torsoLeanDeg(leftShoulder, rightShoulder, leftHip, rightHip);

        Landmark shoulderMid = PoseMath.calculateMidPoint(leftShoulder, rightShoulder);
        float shoulderWidth = PoseMath.calculateDistance(leftShoulder, rightShoulder);
        feature.headYaw = shoulderWidth < 1e-6f
                ? 0f
                : (float) Math.toDegrees(Math.atan2(nose.x - shoulderMid.x, shoulderWidth));

        // 手高於肩（y 軸向下，故手腕 y 小於肩膀 y 表示舉高）
        feature.leftWristAboveShoulder = leftWrist.y < leftShoulder.y - 0.08f;
        feature.rightWristAboveShoulder = rightWrist.y < rightShoulder.y - 0.08f;

        // 軀幹左右傾斜：肩中點與髖中點的水平偏移（正=右傾）
        Landmark hipMid = PoseMath.calculateMidPoint(leftHip, rightHip);
        feature.bodyLean = shoulderMid.x - hipMid.x;

        feature.inferredEventType = inferEventType(
                feature.leftWristAboveShoulder,
                feature.rightWristAboveShoulder,
                feature.bodyLean);

        return feature;
    }

    /**
     * 分析整段序列，每一幀對應一個 PoseFeature。
     */
    public static List<PoseFeature> analyzeSequence(MotionSequence sequence) {
        List<PoseFeature> features = new ArrayList<>();
        if (sequence == null || sequence.frames == null) {
            return features;
        }
        for (PoseFrame frame : sequence.frames) {
            features.add(analyzeFrame(frame));
        }
        return features;
    }

    /**
     * 肩膀連線相對水平的傾斜角（度）。正值代表右肩高於左肩。
     */
    private static float shoulderSlopeDeg(Landmark leftShoulder, Landmark rightShoulder) {
        float dx = rightShoulder.x - leftShoulder.x;
        float dy = rightShoulder.y - leftShoulder.y;
        return (float) Math.toDegrees(Math.atan2(dy, dx));
    }

    /**
     * 軀幹傾斜：肩中點與髖中點的水平偏移（正規化後的角度近似）。
     */
    private static float torsoLeanDeg(Landmark leftShoulder, Landmark rightShoulder,
                                      Landmark leftHip, Landmark rightHip) {
        Landmark shoulderMid = PoseMath.calculateMidPoint(leftShoulder, rightShoulder);
        Landmark hipMid = PoseMath.calculateMidPoint(leftHip, rightHip);
        float width = PoseMath.calculateDistance(leftShoulder, rightShoulder);
        if (width < 1e-6f) {
            return 0f;
        }
        return (float) Math.toDegrees(Math.atan2(shoulderMid.x - hipMid.x, width));
    }

    /**
     * 由數值推導姿勢事件類型，與 MainActivity.detectDanceEvent 同源。
     * 這張對應表是「數值 ↔ 機器人動作」校正的單一入口，日後調閾值只看這裡。
     */
    public static String inferEventType(boolean leftHandUp, boolean rightHandUp, float bodyLean) {
        if (leftHandUp && rightHandUp) {
            return "BOTH_HANDS_UP";
        }
        if (leftHandUp) {
            return "LEFT_HAND_UP";
        }
        if (rightHandUp) {
            return "RIGHT_HAND_UP";
        }
        if (bodyLean < -0.05f) {
            return "LEAN_LEFT";
        }
        if (bodyLean > 0.05f) {
            return "LEAN_RIGHT";
        }
        return "";
    }
}
