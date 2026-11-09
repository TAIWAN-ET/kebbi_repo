package com.example.myapplication.motion;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 單一關節（或單一自由度群）的 2D 軌跡，以及動作辨識需要的幾何特徵。
 *
 * <p>座標標準：x 向右、y 向下（畫面座標），長度單位為「肩寬」。
 * 因此 (0,0) 是兩肩中點，半徑 0.4 代表 0.4 個肩寬。
 *
 * <p>用途：把 PoseFeature 的手腕位置抽成一條時間序列，
 * 再用 winding（繞行角）、閉合度、圓度、方向反轉次數等不變量判斷動作原子。
 *
 * <p>不負責什麼：不讀 PoseFrame、不產生機器人指令、不做檔案 I/O。
 */
public final class Trajectory {

    private final long[] times;
    private final float[] xs;
    private final float[] ys;
    private Stats stats;

    /**
     * 建立軌跡。
     *
     * @param times 時間戳記（毫秒，必須遞增）
     * @param xs    正規化 x 座標
     * @param ys    正規化 y 座標
     */
    public Trajectory(long[] times, float[] xs, float[] ys) {
        if (times == null || xs == null || ys == null
                || times.length != xs.length || times.length != ys.length) {
            throw new IllegalArgumentException("times/xs/ys 長度必須一致");
        }
        if (times.length == 0) {
            throw new IllegalArgumentException("軌跡不可為空");
        }
        this.times = times.clone();
        this.xs = xs.clone();
        this.ys = ys.clone();
    }

    /**
     * 由列表建立軌跡。
     *
     * @param times 時間戳記列表
     * @param xs    x 座標列表
     * @param ys    y 座標列表
     * @return 軌跡
     */
    public static Trajectory of(List<Long> times, List<Float> xs, List<Float> ys) {
        long[] t = new long[times.size()];
        float[] x = new float[times.size()];
        float[] y = new float[times.size()];
        for (int i = 0; i < times.size(); i++) {
            t[i] = times.get(i);
            x[i] = xs.get(i);
            y[i] = ys.get(i);
        }
        return new Trajectory(t, x, y);
    }

    public int size() {
        return times.length;
    }

    public long timeMs(int index) {
        return times[index];
    }

    public float x(int index) {
        return xs[index];
    }

    public float y(int index) {
        return ys[index];
    }

    public long startMs() {
        return times[0];
    }

    public long endMs() {
        return times[times.length - 1];
    }

    public long durationMs() {
        return endMs() - startMs();
    }

    /**
     * 取得子軌跡（時間區間，含頭不含尾）。
     *
     * @param fromIndex 起始索引（含）
     * @param toIndex   結束索引（不含）
     * @return 子軌跡；範圍無效時回傳單點軌跡
     */
    public Trajectory slice(int fromIndex, int toIndex) {
        int from = Math.max(0, fromIndex);
        int to = Math.min(times.length, toIndex);
        if (to - from < 1) {
            int safe = Math.max(0, Math.min(times.length - 1, fromIndex));
            return new Trajectory(new long[]{times[safe]},
                    new float[]{xs[safe]}, new float[]{ys[safe]});
        }
        long[] t = new long[to - from];
        float[] x = new float[to - from];
        float[] y = new float[to - from];
        System.arraycopy(times, from, t, 0, t.length);
        System.arraycopy(xs, from, x, 0, x.length);
        System.arraycopy(ys, from, y, 0, y.length);
        return new Trajectory(t, x, y);
    }

    /**
     * 依時間切出子軌跡（含頭不含尾）。
     *
     * @param startMs 起始時間
     * @param endMs   結束時間
     * @return 子軌跡
     */
    public Trajectory sliceByTime(long startMs, long endMs) {
        int from = 0;
        while (from < times.length && times[from] < startMs) {
            from++;
        }
        int to = from;
        while (to < times.length && times[to] < endMs) {
            to++;
        }
        return slice(from, to);
    }

    /**
     * 移動平均平滑（窗口 3 或 5），用於抑制 MediaPipe 抖動。
     *
     * @param window 窗口大小（奇數，建議 3）
     * @return 平滑後的新軌跡
     */
    public Trajectory smoothed(int window) {
        if (window <= 1 || times.length < 3) {
            return this;
        }
        int half = window / 2;
        float[] sx = new float[times.length];
        float[] sy = new float[times.length];
        for (int i = 0; i < times.length; i++) {
            // 端點不平滑，避免圓形的起點/終點被往內拉、導致圈數少算
            if (i == 0 || i == times.length - 1) {
                sx[i] = xs[i];
                sy[i] = ys[i];
                continue;
            }
            int from = Math.max(0, i - half);
            int to = Math.min(times.length - 1, i + half);
            float sumX = 0f;
            float sumY = 0f;
            for (int j = from; j <= to; j++) {
                sumX += xs[j];
                sumY += ys[j];
            }
            int count = to - from + 1;
            sx[i] = sumX / count;
            sy[i] = sumY / count;
        }
        return new Trajectory(times, sx, sy);
    }

    /**
     * 依固定時間間隔線性重取樣。
     *
     * @param intervalMs 間隔（毫秒）
     * @return 重取樣軌跡
     */
    public Trajectory resample(long intervalMs) {
        if (intervalMs <= 0 || times.length < 2) {
            return this;
        }
        List<Long> rt = new ArrayList<>();
        List<Float> rx = new ArrayList<>();
        List<Float> ry = new ArrayList<>();
        long end = endMs();
        for (long t = startMs(); t <= end; t += intervalMs) {
            rt.add(t);
            rx.add(xAt(t));
            ry.add(yAt(t));
        }
        if (rt.isEmpty() || rt.get(rt.size() - 1) < end) {
            rt.add(end);
            rx.add(xs[times.length - 1]);
            ry.add(ys[times.length - 1]);
        }
        return Trajectory.of(rt, rx, ry);
    }

    /**
     * 線性內插取得任一時刻的 x。
     *
     * @param timeMs 時間
     * @return x 座標
     */
    public float xAt(long timeMs) {
        if (timeMs <= startMs()) {
            return xs[0];
        }
        if (timeMs >= endMs()) {
            return xs[times.length - 1];
        }
        int hi = 1;
        while (hi < times.length && times[hi] < timeMs) {
            hi++;
        }
        int lo = hi - 1;
        float ratio = (timeMs - times[lo]) / (float) Math.max(1L, times[hi] - times[lo]);
        return xs[lo] + (xs[hi] - xs[lo]) * ratio;
    }

    /**
     * 線性內插取得任一時刻的 y。
     *
     * @param timeMs 時間
     * @return y 座標
     */
    public float yAt(long timeMs) {
        if (timeMs <= startMs()) {
            return ys[0];
        }
        if (timeMs >= endMs()) {
            return ys[times.length - 1];
        }
        int hi = 1;
        while (hi < times.length && times[hi] < timeMs) {
            hi++;
        }
        int lo = hi - 1;
        float ratio = (timeMs - times[lo]) / (float) Math.max(1L, times[hi] - times[lo]);
        return ys[lo] + (ys[hi] - ys[lo]) * ratio;
    }

    /**
     * 兩點編號 i 到 i+1 之間的速度（肩寬／秒）。
     *
     * @param index 起點索引
     * @return 速度
     */
    public float speedAt(int index) {
        if (index < 0 || index + 1 >= times.length) {
            return 0f;
        }
        float dt = (times[index + 1] - times[index]) / 1000f;
        if (dt <= 0f) {
            return 0f;
        }
        float dx = xs[index + 1] - xs[index];
        float dy = ys[index + 1] - ys[index];
        return (float) Math.sqrt(dx * dx + dy * dy) / dt;
    }

    /**
     * 取得此軌跡的幾何特徵（快取）。
     *
     * @return 特徵
     */
    public Stats stats() {
        if (stats == null) {
            stats = new Stats(this);
        }
        return stats;
    }

    /**
     * 以 Ramer-Douglas-Peucker 簡化軌跡，供 FREEFORM 與擺盪類動作使用。
     *
     * @param tolerance 容許誤差（肩寬倍數）
     * @return 簡化後的軌跡（保留時間戳）
     */
    public Trajectory simplifyRdp(float tolerance) {
        if (times.length <= 2) {
            return this;
        }
        boolean[] keep = new boolean[times.length];
        keep[0] = true;
        keep[times.length - 1] = true;
        rdp(0, times.length - 1, tolerance, keep);
        int count = 0;
        for (boolean k : keep) {
            if (k) {
                count++;
            }
        }
        long[] t = new long[count];
        float[] x = new float[count];
        float[] y = new float[count];
        int cursor = 0;
        for (int i = 0; i < times.length; i++) {
            if (keep[i]) {
                t[cursor] = times[i];
                x[cursor] = xs[i];
                y[cursor] = ys[i];
                cursor++;
            }
        }
        return new Trajectory(t, x, y);
    }

    private void rdp(int from, int to, float tolerance, boolean[] keep) {
        if (to <= from + 1) {
            return;
        }
        float ax = xs[from];
        float ay = ys[from];
        float bx = xs[to];
        float by = ys[to];
        float dx = bx - ax;
        float dy = by - ay;
        float length = (float) Math.sqrt(dx * dx + dy * dy);
        float maxDistance = -1f;
        int maxIndex = -1;
        for (int i = from + 1; i < to; i++) {
            float distance;
            if (length < 1e-6f) {
                distance = (float) Math.hypot(xs[i] - ax, ys[i] - ay);
            } else {
                // 點到「線段」的距離（不是無限直線），否則折返/停滯段會被誤刪
                float projection = ((xs[i] - ax) * dx + (ys[i] - ay) * dy) / (length * length);
                projection = Math.max(0f, Math.min(1f, projection));
                float closestX = ax + projection * dx;
                float closestY = ay + projection * dy;
                distance = (float) Math.hypot(xs[i] - closestX, ys[i] - closestY);
            }
            if (distance > maxDistance) {
                maxDistance = distance;
                maxIndex = i;
            }
        }
        if (maxDistance > tolerance && maxIndex > from) {
            keep[maxIndex] = true;
            rdp(from, maxIndex, tolerance, keep);
            rdp(maxIndex, to, tolerance, keep);
        }
    }

    /**
     * 回傳可讀摘要。
     *
     * @return 一行摘要
     */
    public String describe() {
        Stats s = stats();
        return String.format(Locale.US,
                "n=%d dur=%dms path=%.3f net=%.3f closure=%.2f rev=%.2f circ=%.2f dir=%s",
                size(), durationMs(), s.pathLength, s.netDisplacement, s.closure,
                s.revolutions, s.circularity, s.clockwise ? "CW" : "CCW");
    }

    /**
     * 軌跡的幾何特徵。
     */
    public static final class Stats {
        public final int sampleCount;
        public final long durationMs;
        public final float pathLength;
        public final float netDisplacement;
        public final float closure;
        public final float signedArea;
        public final float absArea;
        public final float bboxWidth;
        public final float bboxHeight;
        public final float bboxDiag;
        public final float windingRadians;
        public final float revolutions;
        public final boolean clockwise;
        public final float circularity;
        public final float radius;
        public final float centerX;
        public final float centerY;
        public final int directionReversals;
        public final float linearity;
        public final float meanSpeed;
        public final float samplesPerCycle;
        public final boolean closed;

        private Stats(Trajectory t) {
            this.sampleCount = t.size();
            this.durationMs = t.durationMs();

            float path = 0f;
            float minX = Float.MAX_VALUE;
            float maxX = -Float.MAX_VALUE;
            float minY = Float.MAX_VALUE;
            float maxY = -Float.MAX_VALUE;
            float sumX = 0f;
            float sumY = 0f;
            float absDx = 0f;
            float absDy = 0f;
            for (int i = 0; i < t.size(); i++) {
                minX = Math.min(minX, t.x(i));
                maxX = Math.max(maxX, t.x(i));
                minY = Math.min(minY, t.y(i));
                maxY = Math.max(maxY, t.y(i));
                sumX += t.x(i);
                sumY += t.y(i);
                if (i > 0) {
                    path += (float) Math.hypot(t.x(i) - t.x(i - 1), t.y(i) - t.y(i - 1));
                    absDx += Math.abs(t.x(i) - t.x(i - 1));
                    absDy += Math.abs(t.y(i) - t.y(i - 1));
                }
            }
            this.pathLength = path;
            this.bboxWidth = maxX - minX;
            this.bboxHeight = maxY - minY;
            this.bboxDiag = (float) Math.hypot(bboxWidth, bboxHeight);
            this.centerX = sumX / t.size();
            this.centerY = sumY / t.size();
            this.netDisplacement = (float) Math.hypot(
                    t.x(t.size() - 1) - t.x(0), t.y(t.size() - 1) - t.y(0));
            this.closure = path < 1e-6f ? 0f : netDisplacement / path;
            this.linearity = path < 1e-6f ? 0f : netDisplacement / path;
            this.meanSpeed = durationMs <= 0 ? 0f : path / (durationMs / 1000f);

            // Step1：以鞋帶公式計算有號面積（y 向下時，正值 = 畫面順時針）。
            float area2 = 0f;
            for (int i = 0; i < t.size(); i++) {
                int j = (i + 1) % t.size();
                area2 += t.x(i) * t.y(j) - t.x(j) * t.y(i);
            }
            this.signedArea = area2 / 2f;
            this.absArea = Math.abs(signedArea);

            // Step2：以質心累積角度計算繞行圈數（比多邊形轉角更耐雜訊）。
            float winding = 0f;
            float prevAngle = 0f;
            boolean hasPrev = false;
            float maxRadius = 0f;
            for (int i = 0; i < t.size(); i++) {
                float dx = t.x(i) - centerX;
                float dy = t.y(i) - centerY;
                float angle = (float) Math.atan2(dy, dx);
                maxRadius = Math.max(maxRadius, (float) Math.hypot(dx, dy));
                if (hasPrev) {
                    float delta = wrapPi(angle - prevAngle);
                    winding += delta;
                }
                prevAngle = angle;
                hasPrev = true;
            }
            this.windingRadians = winding;
            this.revolutions = Math.abs(winding) / (float) (Math.PI * 2.0);
            this.clockwise = winding > 0f;

            // Step3：圓度以「每圈」為單位，避免多圈時被路徑長平方稀釋。
            float perRev = Math.max(1f, revolutions);
            this.circularity = path < 1e-6f ? 0f
                    : (float) (4.0 * Math.PI * absArea * perRev / (path * path));
            this.radius = maxRadius;

            // Step4：方向反轉次數（取主要軸），供擺盪/揮手判斷。
            this.directionReversals = countReversals(t, absDx >= absDy);

            // Step5：每週期取樣點數。
            float periodMs = revolutions >= 0.5f ? durationMs / Math.max(0.01f, revolutions)
                    : (directionReversals >= 2 ? durationMs * 2f / directionReversals : 0f);
            this.samplesPerCycle = periodMs <= 0f ? sampleCount
                    : sampleCount / Math.max(0.01f, durationMs / periodMs);
            this.closed = closure < 0.45f;
        }

        /**
         * 依幾何特徵估計動作週期。
         *
         * @return 週期（毫秒）；無法估計時回傳 0
         */
        public float periodMs() {
            if (revolutions >= 0.5f) {
                return durationMs / Math.max(0.01f, revolutions);
            }
            if (directionReversals >= 2) {
                return durationMs * 2f / directionReversals;
            }
            return 0f;
        }

        private static int countReversals(Trajectory t, boolean useX) {
            int reversals = 0;
            int lastSign = 0;
            for (int i = 1; i < t.size(); i++) {
                float delta = useX ? t.x(i) - t.x(i - 1) : t.y(i) - t.y(i - 1);
                if (Math.abs(delta) < 1e-4f) {
                    continue;
                }
                int sign = delta > 0 ? 1 : -1;
                if (lastSign != 0 && sign != lastSign) {
                    reversals++;
                }
                lastSign = sign;
            }
            return reversals;
        }

        private static float wrapPi(float angle) {
            while (angle > Math.PI) {
                angle -= (float) (Math.PI * 2.0);
            }
            while (angle < -Math.PI) {
                angle += (float) (Math.PI * 2.0);
            }
            return angle;
        }
    }
}
