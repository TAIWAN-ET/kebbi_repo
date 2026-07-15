package com.example.myapplication;

/**
 * 純 Java 的姿態數學工具。Camera / 評分 / RobotMapper 全部都會重用。
 * 只依賴 Landmark 的 x, y, z，完全不知道 MediaPipe 或其他模型。
 */
public final class PoseMath {

    private PoseMath() {
    }

    /**
     * 三點夾角（度），頂點為 b，向量 ba = a - b、bc = c - b。
     */
    public static float calculateAngle(Landmark a, Landmark b, Landmark c) {
        float bax = a.x - b.x;
        float bay = a.y - b.y;
        float baz = a.z - b.z;
        float bcx = c.x - b.x;
        float bcy = c.y - b.y;
        float bcz = c.z - b.z;

        float dot = bax * bcx + bay * bcy + baz * bcz;
        float magBa = (float) Math.sqrt(bax * bax + bay * bay + baz * baz);
        float magBc = (float) Math.sqrt(bcx * bcx + bcy * bcy + bcz * bcz);
        if (magBa < 1e-6f || magBc < 1e-6f) {
            return 0f;
        }
        float cosine = clamp(dot / (magBa * magBc), -1f, 1f);
        return (float) Math.toDegrees(Math.acos(cosine));
    }

    /**
     * 三維歐氏距離。
     */
    public static float calculateDistance(Landmark a, Landmark b) {
        float dx = a.x - b.x;
        float dy = a.y - b.y;
        float dz = a.z - b.z;
        return (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    /**
     * 兩點中點。z 取平均，visibility/presence 取平均。
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
     * 把座標平移到以 origin 為原點（用於把全身座標對齊到髖/肩中心）。
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
     * 一階低通濾波（指數平滑）。alpha 越大越相信新值（0 < alpha <= 1）。
     * previous 為 null 時直接回傳 current。
     */
    public static Landmark lowPass(Landmark current, Landmark previous, float alpha) {
        if (previous == null) {
            return new Landmark(current.x, current.y, current.z, current.visibility, current.presence);
        }
        return new Landmark(
                previous.x + alpha * (current.x - previous.x),
                previous.y + alpha * (current.y - previous.y),
                previous.z + alpha * (current.z - previous.z),
                current.visibility,
                current.presence);
    }

    /**
     * 以 visibility / presence 判斷該關節點是否可信（評審常問的遮擋/深度處理入口）。
     */
    public static boolean isConfident(Landmark lm, float threshold) {
        return lm.visibility >= threshold && lm.presence >= threshold;
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }
}
