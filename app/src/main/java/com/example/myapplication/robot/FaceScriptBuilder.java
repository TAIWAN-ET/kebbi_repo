package com.example.myapplication.robot;

import com.example.myapplication.analysis.FaceExpressionAnalyzer;
import com.example.myapplication.model.DanceScript;
import com.example.myapplication.model.FaceExpression;
import com.example.myapplication.model.FaceFrame;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 把逐幀的臉部量測變成 Kebbi 的換臉時間表（{@link DanceScript.FaceEvent}）。
 *
 * <p>用途：PC Offload 的表情部分，接在 {@link DanceScriptBuilder} 後面，把結果填進同一份腳本。
 *
 * <p>誰會呼叫它：PC 端 {@code pc.DanceScriptGenerator}。
 *
 * <p>不負責什麼：不跑 MediaPipe、不讀檔、不呼叫 Nuwa SDK。
 *
 * <p>為什麼不逐幀直接換臉：單幀判斷會被眨眼、說話嘴形、偵測雜訊影響，
 * 而 Kebbi 的臉是一段動畫，換太快只會一直閃。所以：
 * <ol>
 *   <li><b>要穩定</b>：新表情要連續 {@link KebbiFaceMap#MIN_STABLE_MS} 以上才算數；
 *       中間夾雜的短暫雜訊也因此被忽略（不會換過去，也不會打斷目前的臉）。
 *       不再另外對分數做低通：低通會把一閃而過的表情拖長，反而通過穩定門檻。</li>
 *   <li><b>要停留</b>：換臉後至少 {@link KebbiFaceMap#MIN_HOLD_MS} 才能再換。</li>
 *   <li><b>對齊影片</b>：離線分析知道「未來」，換臉時間放在表情<b>開始</b>出現的那一幀，
 *       不是確認穩定之後；再提早 {@link KebbiFaceMap#FACE_LEAD_MS} 抵消機器人端的延遲。</li>
 *   <li><b>看不到臉</b>：短暫遮擋維持目前的臉；連續 {@link KebbiFaceMap#FACE_LOST_RESET_MS} 看不到才回預設臉。</li>
 * </ol>
 */
public class FaceScriptBuilder {

    /**
     * 產生換臉事件。
     *
     * <p>Step1：逐幀判斷候選表情（看不到臉的幀沒有候選）。
     * Step2：把相同候選的連續幀合併成片段（短暫看不到臉不打斷片段）。
     * Step3：依穩定 / 停留規則決定哪些片段要換臉，換臉時間＝片段開始 − 提前量。
     *
     * @param frames          依時間升冪的臉部幀
     * @param frameIntervalMs 取樣間隔（毫秒），用來算最後一幀的長度
     * @return 依時間升冪的換臉事件；第一個一定是 t=0 的預設臉；沒有任何臉部幀時為空
     */
    public List<DanceScript.FaceEvent> build(List<FaceFrame> frames, long frameIntervalMs) {
        List<Segment> segments = segment(frames, frameIntervalMs);
        List<DanceScript.FaceEvent> events = new ArrayList<>();
        if (segments.isEmpty()) {
            return events;
        }

        // Step3：開頭先回預設臉（機器人可能停在上一支舞的表情），再依序決定要不要換
        events.add(new DanceScript.FaceEvent(0L, 0L, FaceExpression.NEUTRAL.name(),
                KebbiFaceMap.faceFor(FaceExpression.NEUTRAL), 0f));
        FaceExpression current = FaceExpression.NEUTRAL;
        long lastChangeMs = Long.MIN_VALUE / 2;
        for (Segment seg : segments) {
            if (seg.expression == current || seg.durationMs() < KebbiFaceMap.MIN_STABLE_MS) {
                continue; // 沒變，或一閃而過
            }
            // 上一張臉還沒停留夠久：延後到可以換的時間；延後之後片段已經結束就不換
            long changeMs = Math.max(seg.startMs, lastChangeMs + KebbiFaceMap.MIN_HOLD_MS);
            if (changeMs >= seg.endMs) {
                continue;
            }
            long sendMs = Math.max(0L, changeMs - KebbiFaceMap.FACE_LEAD_MS);
            events.add(new DanceScript.FaceEvent(sendMs, changeMs, seg.expression.name(),
                    KebbiFaceMap.faceFor(seg.expression), seg.meanScore()));
            current = seg.expression;
            lastChangeMs = changeMs;
        }
        // 第一個表情一開始就出現：開頭那個預設臉就多餘了
        if (events.size() > 1 && events.get(1).timeMs == 0L) {
            events.remove(0);
        }
        return events;
    }

    /** Step1 + Step2：逐幀候選表情 → 連續片段。 */
    private static List<Segment> segment(List<FaceFrame> frames, long frameIntervalMs) {
        List<Segment> segments = new ArrayList<>();
        Segment open = null;
        long lostSinceMs = -1L;

        for (FaceFrame frame : frames) {
            FaceExpression candidate;
            float score = 0f;
            if (frame.detected) {
                lostSinceMs = -1L;
                Map<FaceExpression, Float> scores = FaceExpressionAnalyzer.scores(frame);
                candidate = FaceExpressionAnalyzer.best(scores);
                Float s = scores.get(candidate);
                score = s == null ? 0f : s;
            } else {
                // 看不到臉：短時間內延續目前片段；太久就回預設臉
                if (lostSinceMs < 0) {
                    lostSinceMs = frame.timestampMs;
                }
                if (frame.timestampMs - lostSinceMs < KebbiFaceMap.FACE_LOST_RESET_MS) {
                    if (open != null) {
                        open.endMs = frame.timestampMs + frameIntervalMs;
                    }
                    continue;
                }
                candidate = FaceExpression.NEUTRAL;
            }

            if (open == null || open.expression != candidate) {
                open = new Segment(candidate, frame.timestampMs);
                segments.add(open);
            }
            open.endMs = frame.timestampMs + frameIntervalMs;
            if (frame.detected) {
                open.scoreSum += score;
                open.scoreCount++;
            }
        }
        return segments;
    }

    /** 同一個候選表情的連續時間段。 */
    private static final class Segment {
        final FaceExpression expression;
        final long startMs;
        long endMs;
        float scoreSum;
        int scoreCount;

        Segment(FaceExpression expression, long startMs) {
            this.expression = expression;
            this.startMs = startMs;
            this.endMs = startMs;
        }

        long durationMs() {
            return endMs - startMs;
        }

        float meanScore() {
            return expression == FaceExpression.NEUTRAL || scoreCount == 0 ? 0f : scoreSum / scoreCount;
        }
    }
}
