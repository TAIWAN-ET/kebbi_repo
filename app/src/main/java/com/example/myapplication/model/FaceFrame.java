package com.example.myapplication.model;

import java.util.HashMap;
import java.util.Map;

/**
 * 某個時間點的臉部量測：MediaPipe Face Landmarker 的 blendshape 分數。
 *
 * <p>用途：{@code pc/extract_face.py} 寫出 face_blendshapes.csv，
 * {@code io.FaceIO} 讀成這個物件，交給 {@code analysis.FaceExpressionAnalyzer} 判斷表情。
 *
 * <p>不負責什麼：不判斷表情、不認識 Kebbi。純 Java POJO，PC 與 Android 共用。
 */
public class FaceFrame {
    /** 從影片開始算起的時間（毫秒），與姿態 CSV 的 time_ms 相同。 */
    public long timestampMs;
    /** 這一幀有沒有偵測到臉；false 時 {@link #blendshapes} 是空的。 */
    public boolean detected;
    /** blendshape 名稱（例：mouthSmileLeft）→ 分數 0~1。 */
    public Map<String, Float> blendshapes = new HashMap<>();
    /** 人工標註的表情（只有評估用 CSV 才有，一般為空字串）。 */
    public String label = "";

    /**
     * @param name blendshape 名稱
     * @return 分數；沒有這個欄位時回傳 0
     */
    public float get(String name) {
        Float value = blendshapes.get(name);
        return value == null ? 0f : value;
    }
}
