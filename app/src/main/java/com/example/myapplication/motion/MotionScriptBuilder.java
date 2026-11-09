package com.example.myapplication.motion;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 動作腳本建構器：把多條原始軌跡轉成一個 {@link MotionScript}。
 *
 * <p>流程：
 * <pre>
 * 軌跡 → 分段（有動/沒動）→ 遞迴分類（必要時切成複合動作）
 *      → 補 HOLD → 合併相鄰同類 token → MotionScript
 * </pre>
 *
 * <p>關鍵設計：分類不是「猜一個標籤」就結束，而是用「重建誤差」檢查是否值得切成
 * 兩個動作。例如「右手畫圓 + 順勢抬手」若用單一 CIRCLE 表示，
 * 抬手那一段誤差會很大，builder 就會切成 CIRCLE + RAISE。
 */
public final class MotionScriptBuilder {

    /** 表示成本權重（MDL）：每多一個關鍵幀，等效增加的重建誤差。 */
    private static final float MDL_WEIGHT = 0.006f;

    private final String source;
    private final SamplingConfig config;
    private final MotionSegmenter segmenter;
    private final PrimitiveClassifier classifier;
    private final Map<String, Trajectory> tracks = new LinkedHashMap<>();
    private final List<String> warnings = new ArrayList<>();

    /**
     * @param source 來源名稱（video / camera / csv）
     * @param config 抽幀與辨識標準
     */
    public MotionScriptBuilder(String source, SamplingConfig config) {
        this.source = source;
        this.config = config;
        this.segmenter = new MotionSegmenter(config);
        this.classifier = new PrimitiveClassifier(config);
    }

    /**
     * 加入一條軌跡。
     *
     * @param joint 關節名稱（RIGHT_WRIST / LEFT_WRIST / HEAD / BODY）
     * @param track 軌跡（單位：肩寬）
     * @return this
     */
    public MotionScriptBuilder addTrack(String joint, Trajectory track) {
        if (joint != null && track != null && track.size() > 0) {
            tracks.put(joint, track);
        }
        return this;
    }

    /**
     * 由列表加入一條軌跡。
     *
     * @param joint 關節名稱
     * @param times 時間戳記
     * @param xs    x 座標
     * @param ys    y 座標
     * @return this
     */
    public MotionScriptBuilder addTrack(String joint, List<Long> times,
            List<Float> xs, List<Float> ys) {
        return addTrack(joint, Trajectory.of(times, xs, ys));
    }

    /**
     * 建立動作腳本。
     *
     * @return 動作腳本
     */
    public MotionScript build() {
        List<MotionToken> allTokens = new ArrayList<>();
        long duration = 0L;

        for (Map.Entry<String, Trajectory> entry : tracks.entrySet()) {
            String joint = entry.getKey();
            Trajectory raw = entry.getValue();
            duration = Math.max(duration, raw.endMs());

            Trajectory track = raw.size() >= 3 ? raw.smoothed(3) : raw;
            List<MotionToken> jointTokens = buildTrack(joint, raw, track);
            allTokens.addAll(jointTokens);

            for (MotionToken token : jointTokens) {
                duration = Math.max(duration, token.endMs);
                if (token.type != MotionTokenType.HOLD && !token.samplingAdequate) {
                    warnings.add(String.format(Locale.US,
                            "UNDERSAMPLED %s %d-%dms spc=%.1f",
                            token.joint, token.startMs, token.endMs, token.samplesPerCycle));
                }
            }
        }

        allTokens.sort((a, b) -> {
            int byTime = Long.compare(a.startMs, b.startMs);
            return byTime != 0 ? byTime : a.joint.compareTo(b.joint);
        });
        mergeAdjacent(allTokens);

        MotionScript script = MotionScript.create(source, config, duration,
                allTokens, tracks, warnings);
        if (!script.metrics().pass) {
            script.warnings().add(String.format(Locale.US,
                    "RECONSTRUCTION rmse=%.4f > %.4f", script.metrics().rmse,
                    config.maxReconstructionRmse));
        }
        return script;
    }

    private List<MotionToken> buildTrack(String joint, Trajectory raw, Trajectory track) {
        List<MotionToken> tokens = new ArrayList<>();
        List<MotionSegmenter.Episode> episodes = segmenter.episodes(raw);

        // 沒有明顯停頓但整段都在動時，整段視為一個 episode
        if (episodes.isEmpty() && raw.stats().meanSpeed >= config.activeSpeed
                && raw.stats().bboxDiag >= config.deadband * 2f
                && raw.size() >= 3) {
            episodes.add(new MotionSegmenter.Episode(0, raw.size()));
        }

        if (episodes.isEmpty()) {
            MotionToken hold = MotionToken.hold(joint, raw.startMs(), raw.endMs(),
                    raw.x(0), raw.y(0), raw.size());
            tokens.add(hold);
            return tokens;
        }

        for (MotionSegmenter.Episode episode : episodes) {
            classifyRange(joint, raw, raw.slice(episode.from, episode.to), 0, tokens);
        }

        fillGaps(joint, raw, track, tokens);
        return tokens;
    }

    /**
     * 遞迴分類：先用誤差檢查是否值得切成兩個動作。
     */
    private void classifyRange(String joint, Trajectory raw, Trajectory segment,
            int depth, List<MotionToken> out) {
        if (segment.size() < config.minSegmentSamples) {
            return;
        }
        boolean raised = segment.stats().centerY < -0.05f;
        PrimitiveMatch match = classifier.classify(joint, segment, raised);

        MotionToken whole = toToken(joint, raw, segment, match);
        if (depth >= config.maxSplitDepth || segment.size() < config.minSegmentSamples * 2) {
            out.add(whole);
            return;
        }

        // Step1：評估單一 token 的重建誤差（加上表示成本，避免「用很多關鍵幀硬湊」）
        float baseError = localRmse(raw, segment, whole);
        float baseScore = baseError + MDL_WEIGHT * tokenCost(whole);
        float bestScore = Float.MAX_VALUE;
        int bestSplit = -1;

        // Step2：搜尋最佳切點（切成兩個動作是否更準且更省）
        int step = segment.size() > 120 ? 2 : 1;
        for (int split = config.minSegmentSamples;
                split <= segment.size() - config.minSegmentSamples; split += step) {
            Trajectory left = segment.slice(0, split);
            Trajectory right = segment.slice(split, segment.size());
            boolean leftRaised = left.stats().centerY < -0.05f;
            boolean rightRaised = right.stats().centerY < -0.05f;
            PrimitiveMatch leftMatch = classifier.classify(joint, left, leftRaised);
            PrimitiveMatch rightMatch = classifier.classify(joint, right, rightRaised);
            if (leftMatch.type == MotionTokenType.HOLD || rightMatch.type == MotionTokenType.HOLD) {
                continue;
            }
            if (leftMatch.confidence < 0.50f || rightMatch.confidence < 0.50f) {
                continue;
            }
            MotionToken leftToken = toToken(joint, raw, left, leftMatch);
            MotionToken rightToken = toToken(joint, raw, right, rightMatch);
            float leftError = localRmse(raw, left, leftToken);
            float rightError = localRmse(raw, right, rightToken);
            int leftCount = left.size();
            int rightCount = right.size();
            float error = (leftError * leftCount + rightError * rightCount)
                    / Math.max(1, leftCount + rightCount);
            // 表示成本：CIRCLE 只需 2 個參數；折線型需要 keyframe 數
            float cost = tokenCost(leftToken) + tokenCost(rightToken);
            float score = error + MDL_WEIGHT * cost;
            if (score < bestScore) {
                bestScore = score;
                bestSplit = split;
            }
        }

        // Step3：只有「誤差 + 表示成本」明顯下降，或單一分類不成立，才切成兩個
        boolean accepted = match.accepted(config);
        boolean significant = bestSplit > 0
                && bestScore < baseScore * (accepted ? 0.80f : 0.95f);
        if (significant) {
            classifyRange(joint, raw, segment.slice(0, bestSplit), depth + 1, out);
            classifyRange(joint, raw, segment.slice(bestSplit, segment.size()), depth + 1, out);
        } else {
            out.add(whole);
        }
    }

    /**
     * 建立 token；低信心或 UNKNOWN 一律退回 FREEFORM，避免硬猜。
     */
    private MotionToken toToken(String joint, Trajectory raw, Trajectory segment,
            PrimitiveMatch match) {
        long start = segment.startMs();
        long end = segment.endMs();
        int sampleCount = segment.size();
        Trajectory analyzed = match.analyzed != null ? match.analyzed : segment;

        if (match.type == MotionTokenType.CIRCLE) {
            return MotionToken.circle(joint, start, end, segment,
                    match.confidence, config);
        }
        if (match.type == MotionTokenType.HOLD) {
            return MotionToken.hold(joint, start, end,
                    analyzed.x(0), analyzed.y(0), sampleCount);
        }
        if (!match.accepted(config) || match.type == MotionTokenType.FREEFORM) {
            Trajectory simplified = analyzed.simplifyRdp(config.deadband);
            return MotionToken.polyline(joint, MotionTokenType.FREEFORM, start, end,
                    simplified, Math.min(match.confidence, 0.5f), sampleCount,
                    match.periodMs, config);
        }
        Trajectory simplified = match.simplified != null
                ? match.simplified : analyzed.simplifyRdp(config.deadband);
        return MotionToken.polyline(joint, match.type, start, end, simplified,
                match.confidence, sampleCount, match.periodMs, config);
    }

    /**
     * 計算 token 的表示成本（參數數量／關鍵幀數），供 MDL 切分決策使用。
     */
    private float tokenCost(MotionToken token) {
        if (token == null) {
            return 0f;
        }
        if (token.type == MotionTokenType.CIRCLE) {
            return 2f;
        }
        if (token.type == MotionTokenType.HOLD) {
            return 1f;
        }
        return token.keyXs == null ? 2f : token.keyXs.length;
    }

    /**
     * 計算 token 對原始軌跡的 RMSE（只用於比較是否值得切分）。
     */
    private float localRmse(Trajectory raw, Trajectory segment, MotionToken token) {
        double squared = 0.0;
        int count = 0;
        for (int i = 0; i < segment.size(); i++) {
            long t = segment.timeMs(i);
            float[] p = PrimitiveExpander.evaluate(token, t);
            if (Float.isNaN(p[0])) {
                continue;
            }
            // 對應到 raw 的同時間位置
            float ox = raw.xAt(t);
            float oy = raw.yAt(t);
            double dx = ox - p[0];
            double dy = oy - p[1];
            squared += dx * dx + dy * dy;
            count++;
        }
        return count == 0 ? 0f : (float) Math.sqrt(squared / count);
    }

    /**
     * 補上停頓，並保證每個時間點都有 token 覆蓋。
     */
    private void fillGaps(String joint, Trajectory raw, Trajectory track,
            List<MotionToken> tokens) {
        if (tokens.isEmpty()) {
            return;
        }
        tokens.sort((a, b) -> Long.compare(a.startMs, b.startMs));

        MotionToken first = tokens.get(0);
        long leading = first.startMs - track.startMs();
        if (leading >= config.dwellMs) {
            tokens.add(0, MotionToken.hold(joint, track.startMs(), first.startMs,
                    track.x(0), track.y(0), 1));
        } else if (leading > 0) {
            reshapeCircle(first, joint, raw, track.startMs(), first.endMs);
        }

        for (int i = 1; i < tokens.size(); i++) {
            MotionToken prev = tokens.get(i - 1);
            MotionToken next = tokens.get(i);
            long gap = next.startMs - prev.endMs;
            if (gap >= config.dwellMs) {
                long mid = prev.endMs + gap / 2;
                tokens.add(i, MotionToken.hold(joint, prev.endMs, next.startMs,
                        track.xAt(mid), track.yAt(mid), 1));
                i++;
            } else if (gap > 0) {
                // 補齊一小段空隙；若是圓形，用完整時間範圍重新擬合，避免圓心偏移
                reshapeCircle(prev, joint, raw, prev.startMs, next.startMs);
            }
        }

        MotionToken last = tokens.get(tokens.size() - 1);
        long trailing = track.endMs() - last.endMs;
        if (trailing >= config.dwellMs) {
            tokens.add(MotionToken.hold(joint, last.endMs, track.endMs(),
                    track.x(track.size() - 1), track.y(track.size() - 1), 1));
        } else if (trailing > 0) {
            reshapeCircle(last, joint, raw, last.startMs, track.endMs());
        }
    }

    /**
     * 調整 token 的時間範圍；圓形會用新的完整區間重新擬合。
     */
    private void reshapeCircle(MotionToken token, String joint, Trajectory raw,
            long newStart, long newEnd) {
        if (token.type != MotionTokenType.CIRCLE) {
            token.startMs = newStart;
            token.endMs = newEnd;
            return;
        }
        Trajectory fit = raw.sliceByTime(newStart, newEnd + 1);
        if (fit.size() < config.minSegmentSamples) {
            token.startMs = newStart;
            token.endMs = newEnd;
            return;
        }
        MotionToken refit = MotionToken.circle(joint, newStart, newEnd, fit,
                token.confidence, config);
        token.startMs = refit.startMs;
        token.endMs = refit.endMs;
        token.centerX = refit.centerX;
        token.centerY = refit.centerY;
        token.radius = refit.radius;
        token.startAngleRad = refit.startAngleRad;
        token.clockwise = refit.clockwise;
        token.revolutions = refit.revolutions;
        token.periodMs = refit.periodMs;
        token.sampleCount = refit.sampleCount;
        token.samplesPerCycle = refit.samplesPerCycle;
        token.samplingAdequate = refit.samplingAdequate;
    }

    /**
     * 合併相鄰同類 token（HOLD 直接延長，CIRCLE 合併圈數）。
     */
    private void mergeAdjacent(List<MotionToken> tokens) {
        for (int i = 1; i < tokens.size(); i++) {
            MotionToken prev = tokens.get(i - 1);
            MotionToken cur = tokens.get(i);
            if (!prev.joint.equals(cur.joint) || prev.type != cur.type) {
                continue;
            }
            if (cur.startMs - prev.endMs > config.mergeGapMs) {
                continue;
            }
            if (prev.type == MotionTokenType.HOLD) {
                prev.endMs = Math.max(prev.endMs, cur.endMs);
                prev.sampleCount += cur.sampleCount;
                tokens.remove(i);
                i--;
            } else if (prev.type == MotionTokenType.CIRCLE
                    && prev.clockwise == cur.clockwise) {
                float totalRev = prev.revolutions + cur.revolutions;
                long start = prev.startMs;
                long end = Math.max(prev.endMs, cur.endMs);
                prev.endMs = end;
                prev.revolutions = totalRev;
                prev.periodMs = totalRev > 0.01f ? (end - start) / totalRev : prev.periodMs;
                prev.sampleCount += cur.sampleCount;
                prev.confidence = (prev.confidence + cur.confidence) / 2f;
                tokens.remove(i);
                i--;
            }
        }
    }
}
