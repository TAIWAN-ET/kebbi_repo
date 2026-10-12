package com.example.myapplication.analysis;

import com.example.myapplication.model.FaceExpression;
import com.example.myapplication.model.FaceFrame;

import java.util.EnumMap;
import java.util.Map;

/**
 * 由 blendshape 分數判斷單一幀的表情。
 *
 * <p>用途：表情辨識的「判斷」這一半（量測在 {@code pc/extract_face.py}）。
 * 每種表情一個 0~1 分數，最高且超過 {@link #MIN_SCORE} 的就是這一幀的表情，否則 {@link FaceExpression#NEUTRAL}。
 *
 * <p>誰會呼叫它：{@code robot.FaceScriptBuilder}（逐幀算分數再做時間平滑）、
 * PC 端 {@code pc.FaceExpressionEval}（用標註過的照片量準確度）。
 *
 * <p>不負責什麼：不做時間平滑、不決定 Kebbi 何時換臉、不讀檔。純 Java，PC 與 App 共用。
 *
 * <p>規則（分數都先用 {@link #ramp} 換成 0~1）：
 * <ul>
 *   <li>HAPPY：嘴角上揚（mouthSmile）。</li>
 *   <li>SURPRISED：眉毛上揚 × 張嘴，且沒在笑。</li>
 *   <li>ANGRY：用力皺眉，或眉毛下壓且嘴巴張開 / 拉緊；且沒在笑、沒有揚眉。</li>
 *   <li>SAD：嘴角下垂為主、眉頭上揚為輔，且沒在笑、沒張嘴。</li>
 * </ul>
 *
 * <p>門檻是用 1163 張有標註的網路人臉照片（muxspace/facial_expressions，5 類各約 230 張）調的：
 * 整體 47%（隨機猜 20%）；精確度 HAPPY 0.60、SURPRISED 0.71，ANGRY 0.40、SAD 0.43；
 * 拿不準時大多判成 NEUTRAL（寧可不換臉，也不要換錯）。這批照片的標註本身也有不少雜訊。
 * 換資料重調：{@code pc/eval_face.py}（做 CSV）+ {@code pc.FaceExpressionEval}（印混淆矩陣）。
 */
public final class FaceExpressionAnalyzer {

    /** 最高分低於這個值就判成 NEUTRAL。 */
    public static final float MIN_SCORE = 0.3f;

    private FaceExpressionAnalyzer() {
    }

    /**
     * 算出每種表情（不含 NEUTRAL）的分數。
     *
     * @param frame 臉部幀；沒偵測到臉時所有分數為 0
     * @return 表情 → 分數 0~1
     */
    public static Map<FaceExpression, Float> scores(FaceFrame frame) {
        Map<FaceExpression, Float> scores = new EnumMap<>(FaceExpression.class);
        float smile = avg(frame, "mouthSmileLeft", "mouthSmileRight");
        float frown = avg(frame, "mouthFrownLeft", "mouthFrownRight");
        float browInner = frame.get("browInnerUp");
        float browUp = (browInner + avg(frame, "browOuterUpLeft", "browOuterUpRight")) / 2f;
        float browDown = avg(frame, "browDownLeft", "browDownRight");
        float jaw = frame.get("jawOpen");
        float stretch = avg(frame, "mouthStretchLeft", "mouthStretchRight");

        float happy = ramp(smile, 0.05f, 0.6f);
        float raised = ramp(browUp, 0.02f, 0.2f);
        float open = ramp(jaw, 0.05f, 0.2f);
        float notHappy = 1f - happy;

        scores.put(FaceExpression.HAPPY, happy);
        scores.put(FaceExpression.SURPRISED, raised * open * notHappy);
        // 眉毛下壓在一般表情裡也常見：輕微下壓要配合嘴巴張開 / 拉緊，用力皺眉則單獨就算
        float scowl = Math.max(ramp(browDown, 0.6f, 0.9f),
                ramp(browDown, 0.05f, 0.25f) * (0.15f + 0.85f * ramp(jaw + stretch, 0.15f, 0.3f)));
        scores.put(FaceExpression.ANGRY, scowl * notHappy * (1f - raised));
        scores.put(FaceExpression.SAD, (0.85f * ramp(frown, 0.02f, 0.12f) + 0.15f * ramp(browInner, 0.1f, 0.2f))
                * notHappy * (1f - open));
        return scores;
    }

    /**
     * 判斷單一幀的表情。
     *
     * @param frame 臉部幀
     * @return 表情；沒偵測到臉或分數都不夠高時為 NEUTRAL
     */
    public static FaceExpression classify(FaceFrame frame) {
        return best(scores(frame));
    }

    /**
     * 從分數表挑出表情（{@code FaceScriptBuilder} 對平滑後的分數也用這個）。
     *
     * @param scores 表情 → 分數
     * @return 最高分且 ≥ {@link #MIN_SCORE} 的表情，否則 NEUTRAL
     */
    public static FaceExpression best(Map<FaceExpression, Float> scores) {
        FaceExpression best = FaceExpression.NEUTRAL;
        float bestScore = MIN_SCORE;
        for (Map.Entry<FaceExpression, Float> e : scores.entrySet()) {
            if (e.getValue() >= bestScore) {
                best = e.getKey();
                bestScore = e.getValue();
            }
        }
        return best;
    }

    /** 線性把 [lo, hi] 映到 [0, 1]，超出範圍夾住。 */
    static float ramp(float value, float lo, float hi) {
        return Math.max(0f, Math.min(1f, (value - lo) / (hi - lo)));
    }

    private static float avg(FaceFrame frame, String a, String b) {
        return (frame.get(a) + frame.get(b)) / 2f;
    }
}
