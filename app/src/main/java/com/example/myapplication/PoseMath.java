package com.example.myapplication;

/**
 * 純 Java 的姿態數學工具。
 *
 * <p>用途：提供三點夾角、距離、中點、座標平移、低通濾波等基本幾何運算。
 * Camera 模式、評分系統、RobotMapper 全部都會重用這個工具類。
 *
 * <p>誰會呼叫它：
 * {@link PoseAnalyzer#analyzeFrame(PoseFrame)} 計算各種角度時使用，
 * {@link PosePipeline#verify(java.io.File, java.io.File)} 座標比對時使用。
 *
 * <p>不負責什麼：
 * 不解析任何檔案格式，不處理 Android UI 或 MediaPipe 執行期，
 * 不認識任何機器人 SDK 或 API。
 * 只依賴 {@link Landmark} 的 x, y, z，完全不知道 MediaPipe 或其他模型。
 */
public final class PoseMath {

    private PoseMath() {
    }

    /**
     * 計算三點夾角（度）。
     *
     * <p>Step1：計算向量 ba（a - b）和向量 bc（c - b）。
     * Step2：計算兩向量的點積。
     * Step3：計算兩向量的模長。
     * Step4：用反餘弦函數求出夾角（度）。
     *
     * @param a 第一個點（向量 ba 的終點）
     * @param b 頂點點（向量 ba 和 bc 的起點）
     * @param c 第三個點（向量 bc 的終點）
     * @return 三點夾角（度）；若任一向量長度接近零，回傳 0f
     */
    public static float calculateAngle(Landmark a, Landmark b, Landmark c) {
        // Step1：計算向量 ba 和 bc
        float bax = a.x - b.x;
        float bay = a.y - b.y;
        float baz = a.z - b.z;
        float bcx = c.x - b.x;
        float bcy = c.y - b.y;
        float bcz = c.z - b.z;

        // Step2：計算點積
        float dot = bax * bcx + bay * bcy + baz * bcz;

        // Step3：計算向量模長
        float magBa = (float) Math.sqrt(bax * bax + bay * bay + baz * baz);
        float magBc = (float) Math.sqrt(bcx * bcx + bcy * bcy + bcz * bcz);

        // Step4：若向量長度接近零，回傳 0（避免除以零）
        if (magBa < 1e-6f || magBc < 1e-6f) {
            return 0f;
        }

        // Step5：計算餘弦值並夾位到 [-1, 1]，再轉換為角度
        float cosine = clamp(dot / (magBa * magBc), -1f, 1f);
        return (float) Math.toDegrees(Math.acos(cosine));
    }

    /**
     * 計算三維歐氏距離。
     *
     * @param a 第一個點
     * @param b 第二個點
     * @return 两點之間的歐氏距離
     */
    public static float calculateDistance(Landmark a, Landmark b) {
        float dx = a.x - b.x;
        float dy = a.y - b.y;
        float dz = a.z - b.z;
        return (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    /**
     * 計算兩點的中點。
     *
     * <p>z 取平均，visibility/presence 取平均。
     *
     * @param a 第一個點
     * @param b 第二個點
     * @return 兩點的中點 Landmark
     */
    public static Landmark calculateMidPoint(Landmark a, Landmark b) {
        return new Landmark(
                (a.x + b.x) / 2f,
                (a.y + b.y) / 2f,
                (a.z + b.z) / 2f,
                (a.visibility + b.visibility) / 2f,
                (a.presence + b.presence) / 2f);
    }

    /**
     * 把座標平移到以 origin 為原點。
     *
     * <p>用途：把全身座標對齊到髖或肩中心，方便比較不同身高的姿勢。
     *
     * @param lm      要平移的關節點
     * @param origin  原點（平移後 lm 的座標將相對於 origin）
     * @return 平移後的新 Landmark
     */
    public static Landmark normalize(Landmark lm, Landmark origin) {
        return new Landmark(
                lm.x - origin.x,
                lm.y - origin.y,
                lm.z - origin.z,
                lm.visibility,
                lm.presence);
    }

    /**
     * 一階低通濾波（指數平滑）。
     *
     * <p>用途：平滑連續幀之間的座標抖動。
     *
     * <p>Step1：若 previous 為 null，直接回傳 current 的副本。
     * Step2：否則用指數平滑公式計算新座標：previous + alpha * (current - previous)。
     *
     * @param current  當前值
     * @param previous 上一幀的值（為 null 時直接回傳 current）
     * @param alpha    平滑係數（0 < alpha <= 1），越大越相信新值
     * @return 平滑後的新 Landmark
     */
    public static Landmark lowPass(Landmark current, Landmark previous, float alpha) {
        // Step1：previous 為 null 時直接回傳 current
        if (previous == null) {
            return new Landmark(current.x, current.y, current.z, current.visibility, current.presence);
        }

        // Step2：指數平滑
        return new Landmark(
                previous.x + alpha * (current.x - previous.x),
                previous.y + alpha * (current.y - previous.y),
                previous.z + alpha * (current.z - previous.z),
                current.visibility,
                current.presence);
    }

    /**
     * 以 visibility / presence 判斷該關節點是否可信。
     *
     * <p>用途：處理 MediaPipe 的遮擋（Occlusion）與深度誤差問題。
     *
     * @param lm        關節點
     * @param threshold 可信度閾值（建議 0.5）
     * @return 若 visibility 和 presence 均大於等於閾值，回傳 true
     */
    public static boolean isConfident(Landmark lm, float threshold) {
        return lm.visibility >= threshold && lm.presence >= threshold;
    }

    /**
     * 將浮點數夾位到指定範圍。
     *
     * @param value 原始值
     * @param min   最小值
     * @param max   最大值
     * @return 夾位後的值
     */
    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }
}
