package com.example.myapplication.motion;

import java.util.ArrayList;
import java.util.List;

/**
 * 動作分段器：把連續軌跡切成「有在動」的片段（motion episode）。
 *
 * <p>原理：先算每個取樣點的速度，速度低於門檻且位移小於死區者視為停滯；
 * 連續的活動點合成一段。短暫掉速（例如圓形頂點）用 mergeGap 容忍，
 * 避免一個圓被切成好幾段。
 *
 * <p>注意：這裡只切「有動／沒動」；複合動作（圓 + 抬手的細分）
 * 由 {@link MotionScriptBuilder} 的遞迴分類處理。
 */
public final class MotionSegmenter {

    private final SamplingConfig config;

    public MotionSegmenter(SamplingConfig config) {
        this.config = config;
    }

    /**
     * 找出所有活動片段。
     *
     * @param trajectory 軌跡
     * @return 片段列表（索引含頭不含尾）
     */
    public List<Episode> episodes(Trajectory trajectory) {
        List<Episode> result = new ArrayList<>();
        int n = trajectory.size();
        if (n < 2) {
            return result;
        }

        // Step1：標記活動點
        boolean[] active = new boolean[n];
        for (int i = 0; i < n; i++) {
            float speedPrev = i > 0 ? trajectory.speedAt(i - 1) : 0f;
            float speedNext = i + 1 < n ? trajectory.speedAt(i) : 0f;
            float stepPrev = i > 0 ? (float) Math.hypot(
                    trajectory.x(i) - trajectory.x(i - 1),
                    trajectory.y(i) - trajectory.y(i - 1)) : 0f;
            float stepNext = i + 1 < n ? (float) Math.hypot(
                    trajectory.x(i + 1) - trajectory.x(i),
                    trajectory.y(i + 1) - trajectory.y(i)) : 0f;
            active[i] = Math.max(speedPrev, speedNext) >= config.activeSpeed
                    || Math.max(stepPrev, stepNext) >= config.deadband;
        }

        // Step2：以 mergeGap 容忍短暫停頓，合併活動點
        long medianInterval = medianIntervalMs(trajectory);
        int gapAllowance = Math.max(1,
                (int) Math.ceil(config.mergeGapMs / (double) Math.max(1L, medianInterval)));
        int i = 0;
        while (i < n) {
            if (!active[i]) {
                i++;
                continue;
            }
            int start = i;
            int end = i + 1;
            int gap = 0;
            for (int j = i + 1; j < n; j++) {
                if (active[j]) {
                    end = j + 1;
                    gap = 0;
                } else {
                    gap++;
                    if (gap > gapAllowance) {
                        break;
                    }
                }
            }
            // 不做前後 padding：padding 會把「抬手完成後的第一個靜止點」算進抬手，
            // 導致 RAISE 的終點被拉長、重建誤差變大。
            long duration = trajectory.timeMs(end - 1) - trajectory.timeMs(start);
            if (end - start >= config.minSegmentSamples && duration >= config.minSegmentMs) {
                result.add(new Episode(start, end));
            }
            i = Math.max(end, start + 1);
        }
        return result;
    }

    private static long medianIntervalMs(Trajectory trajectory) {
        if (trajectory.size() < 2) {
            return 100L;
        }
        List<Long> intervals = new ArrayList<>();
        for (int i = 1; i < trajectory.size(); i++) {
            long dt = trajectory.timeMs(i) - trajectory.timeMs(i - 1);
            if (dt > 0) {
                intervals.add(dt);
            }
        }
        if (intervals.isEmpty()) {
            return 100L;
        }
        intervals.sort(Long::compareTo);
        return intervals.get(intervals.size() / 2);
    }

    /**
     * 一個活動片段（索引範圍，含頭不含尾）。
     */
    public static final class Episode {
        /** 起始索引。 */
        public final int from;
        /** 結束索引（不含）。 */
        public final int to;

        public Episode(int from, int to) {
            this.from = from;
            this.to = to;
        }

        /**
         * 片段長度（取樣點數）。
         *
         * @return 點數
         */
        public int length() {
            return to - from;
        }
    }
}
