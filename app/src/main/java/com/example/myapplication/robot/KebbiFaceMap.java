package com.example.myapplication.robot;

import com.example.myapplication.model.FaceExpression;

import java.util.EnumMap;
import java.util.Map;

/**
 * 表情 → Kebbi 臉部動畫名稱，以及換臉的時間參數。
 *
 * <p>用途：{@link FaceScriptBuilder} 把辨識出的表情寫進腳本時，查這張表得到要播的臉。
 * 腳本裡存的是「臉的名稱」，機器人端只負責照名稱播放
 * （{@code RobotController.showFace} → {@code UnityFaceManager.playMotion}），
 * 所以改這張表後只要重跑 {@code make_dance.py --skip-extract}，不用重裝 APK。
 *
 * <p>TODO 實機確認：Nuwa SDK 文件只給了一個範例名稱（"J2_Hug"），沒有公布臉部動畫清單。
 * 下面的名稱是 Kebbi 內建說話表情的命名（TTS_ 開頭），需要在機器人上逐一試播。
 * 注意 SDK 的 {@code playMotion} 名稱錯了也不會報錯，只能看臉有沒有變；
 * 播放摘要裡的 faces rejected 只代表臉部服務沒連上。
 */
public final class KebbiFaceMap {

    /** 空字串＝回到 Kebbi 預設（待機）臉，不播動畫。 */
    public static final String DEFAULT_FACE = "";

    /**
     * 換臉指令要比影片中表情出現的時間提早多少（毫秒）。
     *
     * <p>腳本是整份先傳到機器人再播放，所以 Wi-Fi 傳輸延遲不影響對齊；
     * 這裡只補機器人端的延遲：AIDL 呼叫臉部服務 + Unity 動畫從上一張臉過渡到新臉。
     * TODO 實機確認：用高速攝影或錄影逐格比對後調整。
     */
    public static final long FACE_LEAD_MS = 200L;

    /** 換臉後至少維持多久才能再換（毫秒）；臉部動畫本身要時間播，換太快只會看到一直閃。 */
    public static final long MIN_HOLD_MS = 1200L;

    /** 新表情要連續出現多久才算數（毫秒）；過濾眨眼、說話時的瞬間嘴形。 */
    public static final long MIN_STABLE_MS = 600L;

    /** 連續這麼久看不到臉（轉身、遮擋）就回到預設臉（毫秒）。 */
    public static final long FACE_LOST_RESET_MS = 3000L;

    private static final Map<FaceExpression, String> FACES = new EnumMap<>(FaceExpression.class);

    static {
        FACES.put(FaceExpression.NEUTRAL, DEFAULT_FACE);
        FACES.put(FaceExpression.HAPPY, "TTS_JoyA");
        FACES.put(FaceExpression.SURPRISED, "TTS_Surprise");
        FACES.put(FaceExpression.ANGRY, "TTS_AngerA");
        FACES.put(FaceExpression.SAD, "TTS_SadnessA");
    }

    private KebbiFaceMap() {
    }

    /**
     * @param expression 表情
     * @return Kebbi 臉部動畫名稱；{@link #DEFAULT_FACE} 代表回到預設臉
     */
    public static String faceFor(FaceExpression expression) {
        String face = FACES.get(expression);
        return face == null ? DEFAULT_FACE : face;
    }
}
