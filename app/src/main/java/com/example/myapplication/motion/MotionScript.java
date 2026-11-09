package com.example.myapplication.motion;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 動作腳本：一整段舞蹈的「動作原子序列」，也是動作庫的輸出格式。
 *
 * <p>一個 MotionScript = 少量 {@link MotionToken} + 重建統計。
 * 例：原本 300 幀的舞蹈，可能變成
 * 「右手圓圈 1 秒 → 停頓 0.5 秒 → 雙手抬起 0.6 秒 → 放下 0.6 秒」。
 *
 * <p>可輸出：
 * <ul>
 *   <li>{@link #toCompact()}：一行一 token，適合機器人端解析與 Log。</li>
 *   <li>{@link #toJson()}：完整參數與統計，適合存檔、版本控管、動作庫交換。</li>
 * </ul>
 *
 * <p>不負責什麼：不控制馬達、不讀影片、不認識 MediaPipe。
 */
public final class MotionScript {

    /** 格式版本。 */
    public static final String SCHEMA = "kebbi.motion-script/v1";
    /** 比較基準：原始影片常見的 30fps（用來計算指令壓縮率）。 */
    public static final float BASELINE_SOURCE_FPS = 30f;

    private final String source;
    private final SamplingConfig config;
    private final long durationMs;
    private final List<MotionToken> tokens;
    private final Map<String, Trajectory> originals;
    private final List<String> warnings;
    private Metrics metrics;

    MotionScript(String source, SamplingConfig config, long durationMs,
            List<MotionToken> tokens, Map<String, Trajectory> originals, List<String> warnings) {
        this.source = source;
        this.config = config;
        this.durationMs = durationMs;
        this.tokens = tokens;
        this.originals = originals;
        this.warnings = warnings;
    }

    public String source() {
        return source;
    }

    public SamplingConfig config() {
        return config;
    }

    public long durationMs() {
        return durationMs;
    }

    public List<MotionToken> tokens() {
        return tokens;
    }

    /**
     * 取得指定關節的原始軌跡（供重建驗證用，不寫入 JSON）。
     *
     * @param joint 關節名稱
     * @return 軌跡；不存在時回傳 null
     */
    public Trajectory originalTrack(String joint) {
        return originals.get(joint);
    }

    /**
     * 取得所有原始軌跡。
     *
     * @return 關節名稱 → 軌跡
     */
    public Map<String, Trajectory> originalTracks() {
        return originals;
    }

    /**
     * 取得警告訊息（欠取樣、低信心、重建誤差過大等）。
     *
     * @return 警告列表
     */
    public List<String> warnings() {
        return warnings;
    }

    /**
     * 取得重建與壓縮統計（快取）。
     *
     * @return 統計
     */
    public Metrics metrics() {
        if (metrics == null) {
            metrics = new Metrics(this);
        }
        return metrics;
    }

    /**
     * 輸出單行一 token 的緊湊腳本。
     *
     * @return 文字腳本
     */
    public String toCompact() {
        StringBuilder sb = new StringBuilder();
        sb.append("KEBBI-MOTION/").append(SamplingConfig.SCHEMA_VERSION).append('\n');
        sb.append("src=").append(source)
                .append(" dur=").append(durationMs).append("ms")
                .append(" tokens=").append(tokens.size())
                .append(" base=").append(config.baseIntervalMs).append("ms")
                .append(" idealSPC=").append(config.idealSamplesPerCycle)
                .append('\n');
        for (MotionToken token : tokens) {
            sb.append(token.toCompact()).append('\n');
        }
        Metrics m = metrics();
        sb.append(String.format(Locale.US,
                "# rawSamples=%d expansionPoints=%d naiveCommands=%d semanticReduction=%.1f%% commandReduction=%.1f%% rmse=%.4f pass=%s%n",
                m.rawSamples, m.expansionPoints, m.naiveCommands, m.semanticReduction * 100f,
                m.commandReduction * 100f, m.rmse, m.pass));
        for (String warning : warnings) {
            sb.append("# WARN ").append(warning).append('\n');
        }
        return sb.toString();
    }

    /**
     * 輸出完整 JSON。
     *
     * @return JSON 字串
     */
    public String toJson() {
        Metrics m = metrics();
        StringBuilder sb = new StringBuilder();
        sb.append("{\n");
        sb.append("  \"schema\": \"").append(SCHEMA).append("\",\n");
        sb.append("  \"source\": \"").append(escape(source)).append("\",\n");
        sb.append("  \"durationMs\": ").append(durationMs).append(",\n");
        sb.append("  \"sampling\": {");
        sb.append("\"baseIntervalMs\":").append(config.baseIntervalMs).append(',');
        sb.append("\"minIntervalMs\":").append(config.minIntervalMs).append(',');
        sb.append("\"maxIntervalMs\":").append(config.maxIntervalMs).append(',');
        sb.append("\"idealSamplesPerCycle\":").append(config.idealSamplesPerCycle).append(',');
        sb.append("\"minSamplesPerCycle\":").append(config.minSamplesPerCycle).append(',');
        sb.append("\"minConfidence\":").append(config.minConfidence).append(',');
        sb.append("\"maxReconstructionRmse\":").append(config.maxReconstructionRmse);
        sb.append("},\n");
        sb.append("  \"metrics\": {");
        sb.append("\"rawSamples\":").append(m.rawSamples).append(',');
        sb.append("\"tokens\":").append(m.tokenCount).append(',');
        sb.append("\"expansionPoints\":").append(m.expansionPoints).append(',');
        sb.append("\"baselineFps\":").append(BASELINE_SOURCE_FPS).append(',');
        sb.append("\"naiveCommands\":").append(m.naiveCommands).append(',');
        sb.append(String.format(Locale.US, "\"semanticReduction\":%.4f,", m.semanticReduction));
        sb.append(String.format(Locale.US, "\"commandReduction\":%.4f,", m.commandReduction));
        sb.append(String.format(Locale.US, "\"rmse\":%.4f,", m.rmse));
        sb.append(String.format(Locale.US, "\"normalizedRmse\":%.4f,", m.normalizedRmse));
        sb.append(String.format(Locale.US, "\"fidelity\":%.4f,", m.fidelity));
        sb.append("\"pass\":").append(m.pass);
        sb.append("},\n");
        sb.append("  \"tokens\": [\n");
        for (int i = 0; i < tokens.size(); i++) {
            sb.append("    ").append(tokens.get(i).toJson());
            if (i < tokens.size() - 1) {
                sb.append(',');
            }
            sb.append('\n');
        }
        sb.append("  ],\n");
        sb.append("  \"warnings\": [");
        for (int i = 0; i < warnings.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append("\"").append(escape(warnings.get(i))).append("\"");
        }
        sb.append("]\n");
        sb.append("}\n");
        return sb.toString();
    }

    /**
     * 回傳可讀摘要。
     *
     * @return 多行摘要
     */
    public String describe() {
        StringBuilder sb = new StringBuilder();
        sb.append("MotionScript ").append(SCHEMA)
                .append(" src=").append(source)
                .append(" dur=").append(durationMs).append("ms")
                .append(" tokens=").append(tokens.size()).append('\n');
        for (MotionToken token : tokens) {
            sb.append("  ").append(token.toCompact()).append('\n');
        }
        sb.append("  ").append(metrics().describe()).append('\n');
        return sb.toString();
    }

    private static String escape(String value) {
        return value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    /**
     * 壓縮率與重建誤差。
     */
    public static final class Metrics {
        /** 原始取樣點總數（所有軌跡）。 */
        public final int rawSamples;
        /** token 數量。 */
        public final int tokenCount;
        /** 展開後播放關鍵幀總數。 */
        public final int expansionPoints;
        /** 未壓縮時，以 30fps 逐幀控制的指令數。 */
        public final int naiveCommands;
        /** 語意壓縮率：1 - tokens / rawSamples。 */
        public final float semanticReduction;
        /** 指令壓縮率：1 - expansionPoints / naiveCommands。 */
        public final float commandReduction;
        /** 重建 RMSE（肩寬單位）。 */
        public final float rmse;
        /** 正規化 RMSE（除以動作尺度）。 */
        public final float normalizedRmse;
        /** 保真度：1 - normalizedRmse（夾在 0~1）。 */
        public final float fidelity;
        /** 是否通過標準（RMSE <= 門檻）。 */
        public final boolean pass;

        private Metrics(MotionScript script) {
            int samples = 0;
            int points = 0;
            for (Trajectory trajectory : script.originals.values()) {
                samples += trajectory.size();
            }
            for (MotionToken token : script.tokens) {
                points += PrimitiveExpander.expand(token).size();
            }
            this.rawSamples = samples;
            this.tokenCount = script.tokens.size();
            this.expansionPoints = points;
            this.naiveCommands = Math.max(points,
                    Math.round(script.durationMs / 1000f * BASELINE_SOURCE_FPS));
            this.semanticReduction = samples == 0 ? 0f
                    : 1f - tokenCount / (float) samples;
            this.commandReduction = naiveCommands == 0 ? 0f
                    : 1f - points / (float) naiveCommands;

            // Step1：逐軌跡、逐取樣點計算重建誤差
            double squared = 0.0;
            int compared = 0;
            float scaleSum = 0f;
            int scaleCount = 0;
            for (Map.Entry<String, Trajectory> entry : script.originals.entrySet()) {
                Trajectory original = entry.getValue();
                List<MotionToken> jointTokens = new ArrayList<>();
                for (MotionToken token : script.tokens) {
                    if (token.joint.equals(entry.getKey())) {
                        jointTokens.add(token);
                    }
                }
                scaleSum += Math.max(0.1f, original.stats().bboxDiag);
                scaleCount++;
                for (int i = 0; i < original.size(); i++) {
                    long t = original.timeMs(i);
                    MotionToken owner = ownerAt(jointTokens, t);
                    if (owner == null) {
                        continue;
                    }
                    float[] p = PrimitiveExpander.evaluate(owner, t);
                    if (Float.isNaN(p[0]) || Float.isNaN(p[1])) {
                        continue;
                    }
                    double dx = original.x(i) - p[0];
                    double dy = original.y(i) - p[1];
                    squared += dx * dx + dy * dy;
                    compared++;
                }
            }
            this.rmse = compared == 0 ? 0f : (float) Math.sqrt(squared / compared);
            float scale = scaleCount == 0 ? 0.1f : Math.max(0.1f, scaleSum / scaleCount);
            this.normalizedRmse = rmse / scale;
            this.fidelity = clamp01(1f - normalizedRmse);
            this.pass = rmse <= script.config.maxReconstructionRmse;
        }

        private static MotionToken ownerAt(List<MotionToken> tokens, long t) {
            for (MotionToken token : tokens) {
                if (t >= token.startMs && t <= token.endMs) {
                    return token;
                }
            }
            return null;
        }

        private static float clamp01(float value) {
            return Math.max(0f, Math.min(1f, value));
        }

        /**
         * 回傳一行統計。
         *
         * @return 可讀統計
         */
        public String describe() {
            return String.format(Locale.US,
                    "rawSamples=%d tokens=%d expansionPoints=%d naiveCommands=%d semanticReduction=%.1f%% "
                            + "commandReduction=%.1f%% rmse=%.4f normalizedRmse=%.3f fidelity=%.1f%% pass=%s",
                    rawSamples, tokenCount, expansionPoints, naiveCommands, semanticReduction * 100f,
                    commandReduction * 100f, rmse, normalizedRmse, fidelity * 100f, pass);
        }
    }

    /**
     * 建立空腳本（供 builder 使用）。
     *
     * @param source     來源名稱
     * @param config     抽幀標準
     * @param durationMs 長度
     * @param tokens     token 列表
     * @param originals  原始軌跡
     * @param warnings   警告
     * @return 腳本
     */
    static MotionScript create(String source, SamplingConfig config, long durationMs,
            List<MotionToken> tokens, Map<String, Trajectory> originals, List<String> warnings) {
        return new MotionScript(source, config, durationMs, tokens,
                new LinkedHashMap<>(originals), new ArrayList<>(warnings));
    }
}
