package com.example.myapplication.analysis;

import com.example.myapplication.MainActivity;
import com.example.myapplication.math.PoseMath;
import com.example.myapplication.math.PoseMathVerify;
import com.example.myapplication.model.Landmark;
import com.example.myapplication.model.MotionSequence;
import com.example.myapplication.model.PoseFeature;
import com.example.myapplication.model.PoseFrame;
import com.example.myapplication.model.PoseLandmark;

import java.util.ArrayList;
import java.util.List;

/**
 * 把 MotionSequence（PoseFrame 序列）轉換成 PoseFeature 序列。
 *
 * <p>用途：這一層是從「原始座標」邁向「動作理解」的關鍵：輸出人體動作特徵，
 * 而非 Robot 指令。
 *
 * <p>誰會呼叫它：
 * {@link MainActivity} 逐幀計算特徵時呼叫，
 * {@link PoseMathVerify} 做角度驗證時呼叫。
 *
 * <p>不負責什麼：
 * 不計算角度或距離（由 {@link PoseMath} 負責），不處理檔案 I/O，
 * 不處理 Android UI 或 MediaPipe 執行期，不產生機器人指令。
 * 完全不知道 MediaPipe 或 Kebbi。
 *
 * <p>設計原則：純 Java，不依賴 Android，可被 PC 端單元測試直接重用。
 *
 * <p><b>座標選擇</b>：所有「角度」都算在 {@link PoseFrame#getAngleLandmarks()}
 * （有 world landmark 就用公制座標）上；
 * 所有「誰比誰高 / 誰比誰左」的畫面相對位置才用 {@link PoseFrame#landmarks}
 * （normalized 影像座標）。原因見 {@link PoseFrame} 的類別註解。
 */
public final class PoseAnalyzer {

    /** visibility / presence 的可信門檻，與 MediaPipe 預設值一致。 */
    private static final float CONFIDENCE_THRESHOLD = 0.5f;

    /**
     * 中性站姿時「鼻子高於肩線」換算出的角度（度）。
     *
     * <p>頭部俯仰沒有絕對零點，只能量「相對於站直平視」的偏移。
     * 人站直平視時鼻子大約在肩膀中點上方 0.5~0.7 個肩寬處，
     * 換算 atan2(0.6, 1) 約 30 度，所以拿它當基準扣掉。
     * 若實機發現整體偏高或偏低，調這個常數即可。
     */
    private static final float HEAD_PITCH_REST_DEG = 30f;

    /** 手臂抬升角超過這個值就算「舉起來了」（髖-肩-肘夾角，度）。 */
    private static final float ARM_RAISED_DEG = 60f;

    private PoseAnalyzer() {
    }

    /**
     * 分析單一影格，產出該影格的人體動作特徵。
     *
     * <p>Step1：檢查輸入是否有效（frame 不為 null 且包含足夠 landmark）。
     * Step2：取出上半身關鍵點（角度用公制座標、位置用影像座標）。
     * Step3：用 {@link PoseMath} 計算各關節角度。
     * Step4：計算肩膀傾斜角、軀幹側傾角。
     * Step5：計算頭部偏轉角與俯仰角。
     * Step6：判斷手是否高於肩膀、軀幹是否左右傾斜。
     * Step7：評估這一幀的可信度。
     * Step8：由數值推導姿勢事件類型（如 LEFT_HAND_UP、BOTH_HANDS_UP）。
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

        // Step2：兩份座標各司其職
        // img：normalized 影像座標，用來判斷「誰在畫面上比較高 / 比較左」
        // ang：world 公制座標（沒有就退回 img），用來算所有夾角
        List<Landmark> img = frame.landmarks;
        List<Landmark> ang = frame.getAngleLandmarks();

        Landmark leftShoulder = ang.get(PoseLandmark.LEFT_SHOULDER);
        Landmark rightShoulder = ang.get(PoseLandmark.RIGHT_SHOULDER);
        Landmark leftElbow = ang.get(PoseLandmark.LEFT_ELBOW);
        Landmark rightElbow = ang.get(PoseLandmark.RIGHT_ELBOW);
        Landmark leftWrist = ang.get(PoseLandmark.LEFT_WRIST);
        Landmark rightWrist = ang.get(PoseLandmark.RIGHT_WRIST);
        Landmark leftHip = ang.get(PoseLandmark.LEFT_HIP);
        Landmark rightHip = ang.get(PoseLandmark.RIGHT_HIP);
        Landmark leftKnee = ang.get(PoseLandmark.LEFT_KNEE);
        Landmark rightKnee = ang.get(PoseLandmark.RIGHT_KNEE);
        Landmark nose = ang.get(PoseLandmark.NOSE);

        // Step3：關節角度
        // 手肘＝肩-肘-腕（手臂彎曲程度），手臂＝髖-肩-肘（手臂抬多高）。
        // 這兩個必須是不同的算式，否則肩膀馬達和手肘馬達會永遠同步。
        feature.leftElbowAngle = PoseMath.calculateAngle(leftShoulder, leftElbow, leftWrist);
        feature.rightElbowAngle = PoseMath.calculateAngle(rightShoulder, rightElbow, rightWrist);
        feature.leftArmAngle = PoseMath.calculateAngle(leftHip, leftShoulder, leftElbow);
        feature.rightArmAngle = PoseMath.calculateAngle(rightHip, rightShoulder, rightElbow);
        // 髖＝肩-髖-膝（軀幹相對大腿的夾角）。舊版用肩-髖-腕沒有解剖學意義。
        feature.leftHipAngle = PoseMath.calculateAngle(leftShoulder, leftHip, leftKnee);
        feature.rightHipAngle = PoseMath.calculateAngle(rightShoulder, rightHip, rightKnee);

        // Step4：肩膀傾斜角與軀幹側傾角
        feature.shoulderSlope = shoulderSlopeDeg(leftShoulder, rightShoulder);
        feature.torsoLean = torsoLeanDeg(leftShoulder, rightShoulder, leftHip, rightHip);

        // Step5：頭部偏轉角與俯仰角
        Landmark shoulderMid = PoseMath.calculateMidPoint(leftShoulder, rightShoulder);
        float shoulderWidth = PoseMath.calculateDistance(leftShoulder, rightShoulder);
        if (shoulderWidth < 1e-6f) {
            feature.headYaw = 0f;
            feature.headPitch = 0f;
        } else {
            feature.headYaw = (float) Math.toDegrees(
                    Math.atan2(nose.x - shoulderMid.x, shoulderWidth));
            // y 軸向下為正，所以「鼻子在肩線上方」是 shoulderMid.y - nose.y > 0。
            // 扣掉站直平視的基準值後，抬頭為正、低頭為負。
            feature.headPitch = (float) Math.toDegrees(
                    Math.atan2(shoulderMid.y - nose.y, shoulderWidth)) - HEAD_PITCH_REST_DEG;
        }

        // Step6：手是否高於肩膀（影像座標，y 軸向下，故手腕 y 小於肩膀 y 表示舉高）
        Landmark imgLeftShoulder = img.get(PoseLandmark.LEFT_SHOULDER);
        Landmark imgRightShoulder = img.get(PoseLandmark.RIGHT_SHOULDER);
        Landmark imgLeftWrist = img.get(PoseLandmark.LEFT_WRIST);
        Landmark imgRightWrist = img.get(PoseLandmark.RIGHT_WRIST);
        Landmark imgLeftHip = img.get(PoseLandmark.LEFT_HIP);
        Landmark imgRightHip = img.get(PoseLandmark.RIGHT_HIP);

        feature.leftWristAboveShoulder = imgLeftWrist.y < imgLeftShoulder.y - 0.05f;
        feature.rightWristAboveShoulder = imgRightWrist.y < imgRightShoulder.y - 0.05f;

        // Step7：軀幹左右傾斜量（影像座標下肩中點與髖中點的水平偏移，正=右傾）
        // 這是 normalized 單位，keyframe 的 0.03 門檻就是照這個尺度調的，維持不變。
        Landmark imgShoulderMid = PoseMath.calculateMidPoint(imgLeftShoulder, imgRightShoulder);
        Landmark imgHipMid = PoseMath.calculateMidPoint(imgLeftHip, imgRightHip);
        feature.bodyLean = imgShoulderMid.x - imgHipMid.x;

        // Step8：這一幀的上半身關鍵點夠不夠可信
        // MediaPipe 在遮擋時仍會「猜」出座標，不檢查就會產生假動作。
        feature.confident = isUpperBodyConfident(img);
        feature.headConfident = allConfident(img, PoseLandmark.NOSE,
                PoseLandmark.LEFT_SHOULDER, PoseLandmark.RIGHT_SHOULDER);
        feature.leftArmConfident = allConfident(img,
                PoseLandmark.LEFT_SHOULDER, PoseLandmark.RIGHT_SHOULDER,
                PoseLandmark.LEFT_HIP, PoseLandmark.RIGHT_HIP,
                PoseLandmark.LEFT_ELBOW, PoseLandmark.LEFT_WRIST);
        feature.rightArmConfident = allConfident(img,
                PoseLandmark.LEFT_SHOULDER, PoseLandmark.RIGHT_SHOULDER,
                PoseLandmark.LEFT_HIP, PoseLandmark.RIGHT_HIP,
                PoseLandmark.RIGHT_ELBOW, PoseLandmark.RIGHT_WRIST);

        // Step8b：手臂在身體座標系中的方向（需要真的深度；只有 2D 時 hasArmDirection 為 false）
        computeArmDirections(frame, feature);

        // Step9：由數值推導姿勢事件類型
        feature.inferredEventType = inferEventType(
                feature.leftWristAboveShoulder,
                feature.rightWristAboveShoulder,
                feature.bodyLean);
        if (feature.inferredEventType == null || feature.inferredEventType.isEmpty()) {
            feature.inferredEventType = inferEventTypeFromArmAngles(
                    feature.leftArmAngle,
                    feature.rightArmAngle,
                    feature.bodyLean);
        }

        return feature;
    }

    /**
     * 算兩隻上臂（肩→肘）在身體座標系中的方向。
     *
     * <p>身體座標系由 world landmark 建立：左向 L＝右肩→左肩，向上 U＝髖中點→肩中點（扣掉 L 分量），
     * 前方 F＝L×U。座標系假設 x 向右、y 向下、z 遠離鏡頭（右手系；MediaPipe world 與 RTMW3D 轉出的 CSV 都是），
     * 此時面對鏡頭的人 F 指向鏡頭。因為是用身體本身建座標，人轉身、傾斜都不影響結果。
     *
     * <p>沒有深度（world 不存在，或所有點 z 都相同）時不設定，hasArmDirection 保持 false。
     */
    private static void computeArmDirections(PoseFrame frame, PoseFeature feature) {
        if (frame.worldLandmarks == null || frame.worldLandmarks.size() < PoseLandmark.COUNT) {
            return;
        }
        List<Landmark> w = frame.worldLandmarks;
        Landmark ls = w.get(PoseLandmark.LEFT_SHOULDER);
        Landmark rs = w.get(PoseLandmark.RIGHT_SHOULDER);
        Landmark lh = w.get(PoseLandmark.LEFT_HIP);
        Landmark rh = w.get(PoseLandmark.RIGHT_HIP);
        Landmark le = w.get(PoseLandmark.LEFT_ELBOW);
        Landmark re = w.get(PoseLandmark.RIGHT_ELBOW);

        // 2D 來源（z 全 0）沒有前後資訊
        if (Math.abs(ls.z) + Math.abs(rs.z) + Math.abs(le.z) + Math.abs(re.z) < 1e-6f) {
            return;
        }

        float[] left = normalize(ls.x - rs.x, ls.y - rs.y, ls.z - rs.z);
        if (left == null) {
            return;
        }
        float[] up = new float[]{
                (ls.x + rs.x) / 2 - (lh.x + rh.x) / 2,
                (ls.y + rs.y) / 2 - (lh.y + rh.y) / 2,
                (ls.z + rs.z) / 2 - (lh.z + rh.z) / 2};
        float dot = up[0] * left[0] + up[1] * left[1] + up[2] * left[2];
        up = normalize(up[0] - dot * left[0], up[1] - dot * left[1], up[2] - dot * left[2]);
        if (up == null) {
            return;
        }
        float[] fwd = new float[]{
                left[1] * up[2] - left[2] * up[1],
                left[2] * up[0] - left[0] * up[2],
                left[0] * up[1] - left[1] * up[0]};

        float[] dl = normalize(le.x - ls.x, le.y - ls.y, le.z - ls.z);
        float[] dr = normalize(re.x - rs.x, re.y - rs.y, re.z - rs.z);
        if (dl == null || dr == null) {
            return;
        }
        feature.leftArmForward = dot3(dl, fwd);
        feature.leftArmOut = dot3(dl, left);
        feature.leftArmUp = dot3(dl, up);
        feature.rightArmForward = dot3(dr, fwd);
        feature.rightArmOut = -dot3(dr, left); // 右手的外側＝往「左向」的反方向
        feature.rightArmUp = dot3(dr, up);
        feature.hasArmDirection = true;
    }

    private static float dot3(float[] a, float[] b) {
        return a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
    }

    /** 正規化；長度接近 0 時回傳 null。 */
    private static float[] normalize(float x, float y, float z) {
        float n = (float) Math.sqrt(x * x + y * y + z * z);
        return n < 1e-9f ? null : new float[]{x / n, y / n, z / n};
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
     * 檢查上半身關鍵點的 visibility / presence 是否都過門檻。
     *
     * @param img normalized landmark 列表（visibility / presence 來源）
     * @return 全部關鍵點都可信才回傳 true
     */
    private static boolean allConfident(List<Landmark> img, int... ids) {
        for (int id : ids) {
            if (!PoseMath.isConfident(img.get(id), CONFIDENCE_THRESHOLD)) {
                return false;
            }
        }
        return true;
    }

    private static boolean isUpperBodyConfident(List<Landmark> img) {
        int[] required = {
                PoseLandmark.NOSE,
                PoseLandmark.LEFT_SHOULDER, PoseLandmark.RIGHT_SHOULDER,
                PoseLandmark.LEFT_ELBOW, PoseLandmark.RIGHT_ELBOW,
                PoseLandmark.LEFT_WRIST, PoseLandmark.RIGHT_WRIST,
                PoseLandmark.LEFT_HIP, PoseLandmark.RIGHT_HIP,
        };
        for (int id : required) {
            if (!PoseMath.isConfident(img.get(id), CONFIDENCE_THRESHOLD)) {
                return false;
            }
        }
        return true;
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
     * 計算軀幹左右側傾角度。
     *
     * @param leftShoulder  左肩膀 landmark
     * @param rightShoulder 右肩膀 landmark
     * @param leftHip       左髖 landmark
     * @param rightHip      右髖 landmark
     * @return 軀幹側傾角（度，正=上身往畫面右側倒）；肩膀寬度接近零時回傳 0f
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
     * 與 MainActivity.detectDanceEvent 同源。
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
        if (bodyLean < -0.03f) {
            return "LEAN_LEFT";
        }
        if (bodyLean > 0.03f) {
            return "LEAN_RIGHT";
        }
        return "";
    }

    /**
     * 手腕位置判斷不出來時的備援：改看手臂抬升角。
     *
     * <p>手臂抬升角是髖-肩-肘夾角：自然下垂約 20 度、平舉約 90 度、高舉約 170 度。
     *
     * @param leftArmAngle  左手臂抬升角（度）
     * @param rightArmAngle 右手臂抬升角（度）
     * @param bodyLean      軀幹左右傾斜量
     * @return 推導出的事件類型字串
     */
    private static String inferEventTypeFromArmAngles(float leftArmAngle, float rightArmAngle,
                                                      float bodyLean) {
        boolean leftArmRaised = isArmRaised(leftArmAngle);
        boolean rightArmRaised = isArmRaised(rightArmAngle);
        if (leftArmRaised && rightArmRaised) {
            return "BOTH_HANDS_UP";
        }
        if (leftArmRaised) {
            return "LEFT_HAND_UP";
        }
        if (rightArmRaised) {
            return "RIGHT_HAND_UP";
        }
        if (bodyLean < -0.03f) {
            return "LEAN_LEFT";
        }
        if (bodyLean > 0.03f) {
            return "LEAN_RIGHT";
        }
        return "";
    }

    /**
     * 手臂抬得夠高才算舞蹈手勢。
     *
     * <p>舊版寫成 {@code angle > 0 && angle <= 145}，配上當時算錯的手臂角
     * （其實是手肘彎曲角），等於「手肘一彎就算舉手」，
     * 這是事件永遠判不準、閾值怎麼調都不對的主因。
     *
     * @param armAngle 手臂抬升角（髖-肩-肘，度）
     * @return 超過 {@link #ARM_RAISED_DEG} 回傳 true
     */
    private static boolean isArmRaised(float armAngle) {
        return armAngle > ARM_RAISED_DEG;
    }
}
