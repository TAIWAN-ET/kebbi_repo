package com.example.myapplication.io;

import com.example.myapplication.model.DanceScript;
import com.example.myapplication.model.RobotCommand;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * {@link DanceScript} 的 JSON 讀寫。
 *
 * <p>用途：電腦端把腳本寫成 JSON，Kebbi 端讀回來播放。兩邊用同一份程式碼，
 * 格式不會漂移。
 *
 * <p>JSON 格式（version 1）：
 * <pre>
 * {
 *   "version": 1,
 *   "name": "dream_01",
 *   "frameIntervalMs": 200,
 *   "durationMs": 10200,
 *   "sourceFrameCount": 51,
 *   "skippedFrameCount": 3,
 *   "steps": [
 *     {"t": 0, "event": "", "cmds": [{"motor": 1, "deg": 0.0, "speed": 60.0}]}
 *   ]
 * }
 * </pre>
 *
 * <p>不負責什麼：不做映射、不播放、不做網路傳輸。
 *
 * <p>純 Java，沿用 {@link PoseIO} 的極簡 JSON 解析器，不依賴 org.json / Gson。
 */
public final class DanceScriptIO {

    private DanceScriptIO() {
    }

    /**
     * 將腳本寫入 JSON 檔案（UTF-8）。
     *
     * @param file   目標檔案
     * @param script 要寫入的腳本
     * @throws IOException 寫入失敗時拋出
     */
    public static void write(File file, DanceScript script) throws IOException {
        try (Writer writer = new OutputStreamWriter(new FileOutputStream(file), StandardCharsets.UTF_8)) {
            writer.write(toJsonString(script));
        }
    }

    /**
     * 從 JSON 檔案（UTF-8）讀取腳本。
     *
     * @param file JSON 檔案
     * @return 解析後的腳本
     * @throws IOException 讀取失敗，或檔案版本比程式支援的新時拋出
     */
    public static DanceScript read(File file) throws IOException {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append('\n');
            }
        }
        return fromJsonString(sb.toString());
    }

    /**
     * 將腳本轉成 JSON 字串（每一步一行，方便用肉眼 diff）。
     *
     * @param script 要轉換的腳本
     * @return JSON 字串
     */
    public static String toJsonString(DanceScript script) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\n");
        sb.append("  \"version\": ").append(DanceScript.FORMAT_VERSION).append(",\n");
        sb.append("  \"name\": ").append(quote(script.name)).append(",\n");
        sb.append("  \"frameIntervalMs\": ").append(script.frameIntervalMs).append(",\n");
        sb.append("  \"durationMs\": ").append(script.durationMs).append(",\n");
        sb.append("  \"sourceFrameCount\": ").append(script.sourceFrameCount).append(",\n");
        sb.append("  \"skippedFrameCount\": ").append(script.skippedFrameCount).append(",\n");
        sb.append("  \"steps\": [\n");

        // Step1：逐步序列化，每步一行
        for (int i = 0; i < script.steps.size(); i++) {
            DanceScript.Step step = script.steps.get(i);
            sb.append("    {\"t\": ").append(step.timeMs)
                    .append(", \"event\": ").append(quote(step.event))
                    .append(", \"cmds\": [");

            // Step2：逐指令序列化（角度與速度保留一位小數）
            for (int j = 0; j < step.commands.size(); j++) {
                RobotCommand cmd = step.commands.get(j);
                sb.append(String.format(Locale.US, "{\"motor\": %d, \"deg\": %.1f, \"speed\": %.1f}",
                        cmd.motorId, cmd.degree, cmd.speedDegPerSec));
                if (j < step.commands.size() - 1) {
                    sb.append(", ");
                }
            }
            sb.append("]}");
            if (i < script.steps.size() - 1) {
                sb.append(",");
            }
            sb.append("\n");
        }

        sb.append("  ]\n");
        sb.append("}\n");
        return sb.toString();
    }

    /**
     * 將 JSON 字串解析成腳本。
     *
     * @param json JSON 字串
     * @return 解析後的腳本
     * @throws IOException 檔案版本比程式支援的新時拋出（寧可拒播，也不要亂動馬達）
     */
    @SuppressWarnings("unchecked")
    public static DanceScript fromJsonString(String json) throws IOException {
        Object parsed;
        try {
            parsed = new PoseIO.JsonParser(json).parse();
        } catch (RuntimeException e) {
            throw new IOException("malformed JSON", e);
        }
        if (!(parsed instanceof Map)) {
            throw new IOException("not a JSON object");
        }
        Map<String, Object> root = (Map<String, Object>) parsed;

        // Step1：版本檢查
        DanceScript script = new DanceScript();
        script.version = (int) asLong(root.get("version"));
        if (script.version > DanceScript.FORMAT_VERSION) {
            throw new IOException("DanceScript version " + script.version
                    + " is newer than supported version " + DanceScript.FORMAT_VERSION);
        }

        // Step2：表頭欄位
        Object name = root.get("name");
        script.name = name instanceof String ? (String) name : "";
        script.frameIntervalMs = asLong(root.get("frameIntervalMs"));
        script.durationMs = asLong(root.get("durationMs"));
        script.sourceFrameCount = (int) asLong(root.get("sourceFrameCount"));
        script.skippedFrameCount = (int) asLong(root.get("skippedFrameCount"));

        // Step3：逐步解析
        Object stepsObj = root.get("steps");
        if (stepsObj instanceof List) {
            for (Object stepObj : (List<Object>) stepsObj) {
                if (!(stepObj instanceof Map)) {
                    continue;
                }
                Map<String, Object> stepMap = (Map<String, Object>) stepObj;
                Object event = stepMap.get("event");

                List<RobotCommand> commands = new ArrayList<>();
                Object cmdsObj = stepMap.get("cmds");
                if (cmdsObj instanceof List) {
                    for (Object cmdObj : (List<Object>) cmdsObj) {
                        if (!(cmdObj instanceof Map)) {
                            continue;
                        }
                        Map<String, Object> cmdMap = (Map<String, Object>) cmdObj;
                        commands.add(new RobotCommand(
                                (int) asLong(cmdMap.get("motor")),
                                (float) asDouble(cmdMap.get("deg")),
                                (float) asDouble(cmdMap.get("speed"))));
                    }
                }
                script.steps.add(new DanceScript.Step(
                        asLong(stepMap.get("t")),
                        event instanceof String ? (String) event : "",
                        commands));
            }
        }
        return script;
    }

    private static long asLong(Object value) {
        return value instanceof Number ? ((Number) value).longValue() : 0L;
    }

    private static double asDouble(Object value) {
        return value instanceof Number ? ((Number) value).doubleValue() : 0.0;
    }

    private static String quote(String text) {
        if (text == null) {
            return "\"\"";
        }
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '"' || c == '\\') {
                sb.append('\\').append(c);
            } else if (c < 0x20) {
                sb.append(String.format(Locale.US, "\\u%04x", (int) c));
            } else {
                sb.append(c);
            }
        }
        sb.append('"');
        return sb.toString();
    }
}
