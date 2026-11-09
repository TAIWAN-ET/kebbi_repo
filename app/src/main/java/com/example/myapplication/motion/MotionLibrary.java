package com.example.myapplication.motion;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 動作庫（Motion Library）：定義「有哪些動作原子、如何辨識、機器人怎麼執行」。
 *
 * <p>動作庫不是模型檔，而是可版本控管的規格表。新增一個動作 =
 * 新增一個 {@link Entry}（必要時加一個分類規則），不必改播放器。
 *
 * <p>v1 內建 12 種原子：HOLD、CIRCLE、SWING、WAVE、RAISE、LOWER、
 * TURN_LEFT、TURN_RIGHT、NOD、SHAKE、LEAN_LEFT、LEAN_RIGHT、FREEFORM。
 */
public final class MotionLibrary {

    /** 動作庫格式版本。 */
    public static final String SCHEMA = "kebbi.motion-library/v1";

    private static final List<Entry> ENTRIES = buildEntries();

    private MotionLibrary() {
    }

    /**
     * 取得所有動作庫條目。
     *
     * @return 條目列表（不可變）
     */
    public static List<Entry> entries() {
        return ENTRIES;
    }

    /**
     * 依 token 類型取得條目。
     *
     * @param type 動作類型
     * @return 條目；找不到時回傳 FREEFORM
     */
    public static Entry entryFor(MotionTokenType type) {
        for (Entry entry : ENTRIES) {
            if (entry.type == type) {
                return entry;
            }
        }
        return ENTRIES.get(ENTRIES.size() - 1);
    }

    /**
     * 取得機器人執行碼。
     *
     * @param token 動作 token
     * @return 執行碼（例如 ARM_CIRCLE_R）
     */
    public static String robotActionFor(MotionToken token) {
        if (token == null) {
            return "NONE";
        }
        String side = token.joint != null && token.joint.toUpperCase(Locale.US).contains("LEFT")
                ? "_L"
                : token.joint != null && token.joint.toUpperCase(Locale.US).contains("RIGHT")
                        ? "_R" : "";
        switch (token.type) {
            case CIRCLE:
                return "ARM_CIRCLE" + side;
            case SWING:
                return "ARM_SWING" + side;
            case WAVE:
                return "ARM_WAVE" + side;
            case RAISE:
                return "ARM_RAISE" + side;
            case LOWER:
                return "ARM_LOWER" + side;
            case TURN_LEFT:
            case TURN_RIGHT:
                return "HEAD_TURN";
            case NOD:
                return "HEAD_NOD";
            case SHAKE:
                return "HEAD_SHAKE";
            case LEAN_LEFT:
            case LEAN_RIGHT:
                return "TORSO_LEAN";
            case HOLD:
                return "HOLD";
            default:
                return "KEYFRAME_FOLLOW";
        }
    }

    /**
     * 輸出動作庫 JSON（供文件、PC 工具與機器人端共用）。
     *
     * @return JSON 字串
     */
    public static String toJson() {
        SamplingConfig config = SamplingConfig.standard();
        StringBuilder sb = new StringBuilder();
        sb.append("{\n");
        sb.append("  \"schema\": \"").append(SCHEMA).append("\",\n");
        sb.append("  \"units\": {\"length\": \"shoulderWidth\", \"angle\": \"degree\", \"time\": \"ms\"},\n");
        sb.append("  \"sampling\": {");
        sb.append("\"baseIntervalMs\":").append(config.baseIntervalMs).append(',');
        sb.append("\"idealSamplesPerCycle\":").append(config.idealSamplesPerCycle).append(',');
        sb.append("\"minSamplesPerCycle\":").append(config.minSamplesPerCycle).append(',');
        sb.append("\"dwellMs\":").append(config.dwellMs).append(',');
        sb.append("\"activeSpeed\":").append(config.activeSpeed).append(',');
        sb.append("\"deadband\":").append(config.deadband);
        sb.append("},\n");
        sb.append("  \"acceptance\": {");
        sb.append("\"minConfidence\":").append(config.minConfidence).append(',');
        sb.append("\"maxReconstructionRmse\":").append(config.maxReconstructionRmse);
        sb.append("},\n");
        sb.append("  \"primitives\": [\n");
        for (int i = 0; i < ENTRIES.size(); i++) {
            sb.append("    ").append(ENTRIES.get(i).toJson());
            if (i < ENTRIES.size() - 1) {
                sb.append(',');
            }
            sb.append('\n');
        }
        sb.append("  ]\n");
        sb.append("}\n");
        return sb.toString();
    }

    private static List<Entry> buildEntries() {
        List<Entry> list = new ArrayList<>();
        list.add(new Entry("HOLD", MotionTokenType.HOLD, "靜止/停頓",
                "pathLength < deadband 或速度 < activeSpeed 持續 dwellMs",
                "無（保持目前姿勢）", "HOLD", 1));
        list.add(new Entry("ARM_CIRCLE", MotionTokenType.CIRCLE, "手臂畫圓（可多圈）",
                "revolutions>=0.65, closure<=0.45, circularity>=0.45, spc>=5",
                "半徑 r、方向 CW/CCW、圈數 rev、週期 T", "ARM_CIRCLE", 8));
        list.add(new Entry("ARM_SWING", MotionTokenType.SWING, "手臂直線來回擺盪",
                "directionReversals>=2 且 circularity 低",
                "振幅、週期、方向", "ARM_SWING", 8));
        list.add(new Entry("ARM_WAVE", MotionTokenType.WAVE, "手高於肩的揮手",
                "同 SWING，且手腕中心高於肩膀",
                "振幅、週期", "ARM_WAVE", 8));
        list.add(new Entry("ARM_RAISE", MotionTokenType.RAISE, "單向抬起手臂",
                "linearity>=0.55、dy<0、|dy|>0.05",
                "起點、終點、時間", "ARM_RAISE", 2));
        list.add(new Entry("ARM_LOWER", MotionTokenType.LOWER, "單向放下手臂",
                "linearity>=0.55、dy>0、|dy|>0.05",
                "起點、終點、時間", "ARM_LOWER", 2));
        list.add(new Entry("HEAD_TURN_L", MotionTokenType.TURN_LEFT, "頭/軀幹向左轉",
                "HEAD 軌跡、dx<0、linearity>=0.55", "角度、時間", "HEAD_TURN", 2));
        list.add(new Entry("HEAD_TURN_R", MotionTokenType.TURN_RIGHT, "頭/軀幹向右轉",
                "HEAD 軌跡、dx>0、linearity>=0.55", "角度、時間", "HEAD_TURN", 2));
        list.add(new Entry("HEAD_NOD", MotionTokenType.NOD, "點頭",
                "HEAD 軌跡、垂直方向反轉>=2", "次數、週期", "HEAD_NOD", 8));
        list.add(new Entry("HEAD_SHAKE", MotionTokenType.SHAKE, "搖頭",
                "HEAD 軌跡、水平方向反轉>=2", "次數、週期", "HEAD_SHAKE", 8));
        list.add(new Entry("TORSO_LEAN_L", MotionTokenType.LEAN_LEFT, "軀幹左傾",
                "BODY 軌跡、dx<0、linearity>=0.55", "角度、時間", "TORSO_LEAN", 2));
        list.add(new Entry("TORSO_LEAN_R", MotionTokenType.LEAN_RIGHT, "軀幹右傾",
                "BODY 軌跡、dx>0、linearity>=0.55", "角度、時間", "TORSO_LEAN", 2));
        list.add(new Entry("FREEFORM", MotionTokenType.FREEFORM, "未辨識但保留的動作",
                "低於信心門檻或重建誤差過大", "RDP 稀疏關鍵幀", "KEYFRAME_FOLLOW", 4));
        return java.util.Collections.unmodifiableList(list);
    }

    /**
     * 一個動作庫條目。
     */
    public static final class Entry {
        public final String id;
        public final MotionTokenType type;
        public final String description;
        public final String matchRule;
        public final String parameters;
        public final String robotAction;
        public final int keyframesPerCycle;

        Entry(String id, MotionTokenType type, String description, String matchRule,
                String parameters, String robotAction, int keyframesPerCycle) {
            this.id = id;
            this.type = type;
            this.description = description;
            this.matchRule = matchRule;
            this.parameters = parameters;
            this.robotAction = robotAction;
            this.keyframesPerCycle = keyframesPerCycle;
        }

        String toJson() {
            return String.format(Locale.US,
                    "{\"id\":\"%s\",\"token\":\"%s\",\"description\":\"%s\","
                            + "\"match\":\"%s\",\"parameters\":\"%s\","
                            + "\"robotAction\":\"%s\",\"keyframesPerCycle\":%d}",
                    id, type.code(), description, matchRule, parameters,
                    robotAction, keyframesPerCycle);
        }
    }
}
