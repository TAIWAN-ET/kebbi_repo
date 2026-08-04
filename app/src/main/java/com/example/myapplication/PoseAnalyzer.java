package com.example.myapplication;

import java.util.ArrayList;
import java.util.List;

/**
 * 把 MotionSequence（PoseFrame 序列）轉換成 PoseFeature 序列。
 *
 * <p>用途：這一層是從「原始座標」邁向「動作理解」的關鍵：輸出人體動作特徵，
 * 而非 Robot 指令。
 *
 * <p>誰會呼叫它：
 * {@link MainActivity#analyzeBuiltInClip()} 和 {@link MainActivity#analyzeDanceVideo(android.net.Uri)}
 * 逐幀計算特徵時呼叫，
 * {@link PoseMathVerify} 做角度驗證時呼叫。
 *
 * <p>不負責什麼：
 * 不計算角度或距離（由 {@link PoseMath} 負責），不處理檔案 I/O，
 * 不處理 Android UI 或 MediaPipe 執行期，不產生機器人指令。
 * 完全不知道 MediaPipe 或 Kebbi。
 *
 * <p>設計原則：純 Java，不依賴 Android，可被 PC 端單元測試直接重用。
 */
public final class PoseAnalyzer {

    private PoseAnalyzer() {
    }

    /**
     * 分析單一影格，產出該影格的人體動作特徵。
     *
     * <p>Step1：檢查輸入是否有效（frame 不為 null 且包含足夠 landmark）。
     * Step2：取出上半身關鍵點（肩膀、肘部、手腕、髖部、鼻尖）。
     * Step3：用 {@link PoseMath} 計算各關節角度。
     * Step4：計算肩膀傾斜角、軀幹傾斜角、頭部偏轉角。
     * Step5：判斷手是否高於肩膀、軀幹是否左右傾斜。
     * Step6：由數值推導姿勢事件類型（如 LEFT_HAND_UP、BOTH_HANDS_UP）。
     *
     * @param frame 要分析的姿態影格
     * @return 該影格的人體動作特徵；若輸入無效，回傳空的 PoseFeature
     */
    public static PoseFeature analyzeFrame(PoseFrame frame) {
        PoseFeature feature = new PoseFeature();

        // Step1：檢查輸入是否有效
        if (frame == null || frame.landmarks == null
                || frame.landmarks.size() < PoseLandmark.COUNT) {
            return feature;
        }
        List<Landmark> lm = frame.landmarks;

        // Step2：取出上半身關鍵點
        Landmark leftShoulder = lm.get(PoseLandmark.LEFT_SHOULDER);
        Landmark rightShoulder = lm.get(PoseLandmark.RIGHT_SHOULDER);
        Landmark leftElbow = lm.get(PoseLandmark.LEFT_ELBOW);
        Landmark rightElbow = lm.get(PoseLandmark.RIGHT_ELBOW);
        Landmark leftWrist = lm.get(PoseLandmark.LEFT_WRIST);
        Landmark rightWrist = lm.get(PoseLandmark.RIGHT_WRIST);
        Landmark leftHip = lm.get(PoseLandmark.LEFT_HIP);
        Landmark rightHip = lm.get(PoseLandmark.RIGHT_HIP);
        Landmark nose = lm.get(PoseLandmark.NOSE);

        // Step3：用 PoseMath 計算各關節角度
        feature.leftElbowAngle = PoseMath.calculateAngle(leftShoulder, leftElbow, leftWrist);
        feature.rightElbowAngle = PoseMath.calculateAngle(rightShoulder, rightElbow, rightWrist);
        feature.leftArmAngle = PoseMath.calculateAngle(leftShoulder, leftElbow, leftWrist);
        feature.rightArmAngle = PoseMath.calculateAngle(rightShoulder, rightElbow, rightWrist);
        feature.leftHipAngle = PoseMath.calculateAngle(leftShoulder, leftHip, leftWrist);
        feature.rightHipAngle = PoseMath.calculateAngle(rightShoulder, rightHip, rightWrist);

        // Step4：計算肩膀傾斜角與軀幹傾斜角
        feature.shoulderSlope = shoulderSlopeDeg(leftShoulder, rightShoulder);
        feature.torsoLean = torsoLeanDeg(leftShoulder, rightShoulder, leftHip, rightHip);

        // Step5：計算頭部偏轉角
        Landmark shoulderMid = PoseMath.calculateMidPoint(leftShoulder, rightShoulder);
        float shoulderWidth = PoseMath.calculateDistance(leftShoulder, rightShoulder);
        feature.headYaw = shoulderWidth < 1e-6f
                ? 0f
                : (float) Math.toDegrees(Math.atan2(nose.x - shoulderMid.x, shoulderWidth));

        // Step6：判斷手是否高於肩膀（y 軸向下，故手腕 y 小於肩膀 y 表示舉高）
        feature.leftWristAboveShoulder = leftWrist.y < leftShoulder.y - 0.08f;
        feature.rightWristAboveShoulder = rightWrist.y < rightShoulder.y - 0.08f;

        // Step7：計算軀幹左右傾斜量（肩中點與髖中點的水平偏移，正=右傾）
        Landmark hipMid = PoseMath.calculateMidPoint(leftHip, rightHip);
        feature.bodyLean = shoulderMid.x - hipMid.x;

        // Step8：由數值推導姿勢事件類型
        feature.inferredEventType = inferEventType(
                feature.leftWristAboveShoulder,
                feature.rightWristAboveShoulder,
                feature.bodyLean);

        return feature;
    }

    /**
     * 分析整段序列，每一幀對應一個 PoseFeature。
     *
     * @param sequence 要分析的動作序列
     * @return 每幀對應的 PoseFeature 列表；若 sequence 為 null 或 frames 為 null，回傳空列表
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
     * 計算肩膀連線相對水平的傾斜角（度）。
     *
     * @param leftShoulder  左肩膀 landmark
     * @param rightShoulder 右肩膀 landmark
     * @return 肩膀傾斜角（度）；正值代表右肩高於左肩
     */
    private static float shoulderSlopeDeg(Landmark leftShoulder, Landmark rightShoulder) {
        float dx = rightShoulder.x - leftShoulder.x;
        float dy = rightShoulder.y - leftShoulder.y;
        return (float) Math.toDegrees(Math.atan2(dy, dx));
    }

    /**
     * 計算軀幹傾斜角度（正規化後的角度近似）。
     *
     * @param leftShoulder  左肩膀 landmark
     * @param rightShoulder 右肩膀 landmark
     * @param leftHip       左髖 landmark
     * @param rightHip      右髖 landmark
     * @return 軀幹傾斜角（度）；肩膀寬度接近零時回傳 0f
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
     * 由數值推導姿勢事件類型。
     *
     * <p>這張對應表是「數值 ↔ 機器人動作」校正的單一入口，日後調閾值只看這裡。
     * 與 {@link MainActivity#detectDanceEvent} 同源。
     *
     * @param leftHandUp  左手是否高於肩膀
     * @param rightHandUp 右手是否高於肩膀
     * @param bodyLean    軀幹左右傾斜量（正=右傾，負=左傾）
     * @return 姿勢事件類型字串（LEFT_HAND_UP / RIGHT_HAND_UP / BOTH_HANDS_UP /
     *         LEAN_LEFT / LEAN_RIGHT / 空字串）
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
