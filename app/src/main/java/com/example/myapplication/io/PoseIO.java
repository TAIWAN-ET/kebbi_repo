package com.example.myapplication.io;

import com.example.myapplication.MainActivity;
import com.example.myapplication.model.Landmark;
import com.example.myapplication.model.MotionSequence;
import com.example.myapplication.model.MotionSource;
import com.example.myapplication.model.PoseFrame;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * PoseFrame 資料的輸入/輸出工具，集中所有 CSV 與 JSON 的讀寫。
 *
 * <p>用途：提供 CSV 和 JSON 與 {@link MotionSequence} 之間的雙向轉換。
 * MainActivity 永遠只呼叫 PoseIO，不自己處理序列化細節。
 *
 * <p>誰會呼叫它：
 * {@link PosePipeline#verify(java.io.File, java.io.File)} 做 round-trip 驗證時呼叫，
 * {@link MainActivity} 在分析影片時呼叫寫入 CSV。
 *
 * <p>不負責什麼：
 * 不計算任何角度或距離，不處理 Android UI 或 MediaPipe 執行期，
 * 不認識任何機器人 SDK 或 API。
 *
 * <p>使用 Android 內建套件以外的「純 Java」實作（自寫極簡 JSON 解析），
 * 因此本類別不依賴 Android，PC Java / 單元測試也能直接重用。
 *
 * <p>提供的轉換（構成 P0-A round-trip）：
 * <ul>
 *   <li>readCsv(File)   : CSV  -> MotionSequence</li>
 *   <li>writeCsv(File)  : MotionSequence -> CSV</li>
 *   <li>readJson(File)  : JSON -> MotionSequence</li>
 *   <li>writeJson(File) : MotionSequence -> JSON</li>
 * </ul>
 */
public final class PoseIO {

    private PoseIO() {
    }

    /**
     * 將 MotionSequence 寫入 CSV 檔案。
     *
     * <p>CSV 格式：
     * time_ms,pose_index,landmark_index,x,y,z,visibility,presence
     *
     * @param file     目標 CSV 檔案
     * @param sequence 要寫入的動作序列
     * @throws IOException 檔案寫入失敗時拋出
     */
    public static void writeCsv(File file, MotionSequence sequence) throws IOException {
        try (FileWriter writer = new FileWriter(file)) {
            // Step1：寫入 CSV 標頭
            writer.write("time_ms,pose_index,landmark_index,x,y,z,visibility,presence\n");

            // Step2：逐幀逐 landmark 寫入資料
            for (PoseFrame frame : sequence.frames) {
                for (int li = 0; li < frame.landmarks.size(); li++) {
                    Landmark lm = frame.landmarks.get(li);
                    writer.write(String.format(Locale.US, "%d,0,%d,%f,%f,%f,%f,%f\n",
                            frame.timestampMs, li, lm.x, lm.y, lm.z, lm.visibility, lm.presence));
                }
            }
        }
    }

    /**
     * 從 CSV 檔案讀取並重組成 MotionSequence。
     *
     * <p>使用 TreeMap 依 landmarkIndex 定位，即使 CSV 順序錯亂（如 0,2,1,3）也能正確排回，
     * 不會產生錯位的 Landmark。
     *
     * @param file CSV 檔案
     * @return 解析後的 MotionSequence；若檔案為空，回傳空的 MotionSequence
     * @throws IOException 檔案讀取失敗時拋出
     */
    public static MotionSequence readCsv(File file) throws IOException {
        List<PoseFrame> frames = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            // Step1：跳過 CSV 標頭
            String line = reader.readLine();
            if (line == null) {
                return new MotionSequence(MotionSource.VIDEO, 0f, frames);
            }

            long currentTimestamp = Long.MIN_VALUE;
            TreeMap<Integer, Landmark> current = null;

            // Step2：逐行讀取並按時間戳記分組
            while ((line = reader.readLine()) != null) {
                if (line.trim().isEmpty()) {
                    continue;
                }
                String[] parts = line.split(",");
                if (parts.length < 8) {
                    continue;
                }

                long timeMs = Long.parseLong(parts[0]);
                int landmarkIndex = Integer.parseInt(parts[2]);
                float x = Float.parseFloat(parts[3]);
                float y = Float.parseFloat(parts[4]);
                float z = Float.parseFloat(parts[5]);
                float visibility = Float.parseFloat(parts[6]);
                float presence = Float.parseFloat(parts[7]);

                // Step3：若時間戳記改變，將上一幀加入列表並開始新的一幀
                if (timeMs != currentTimestamp) {
                    if (current != null && currentTimestamp != Long.MIN_VALUE) {
                        frames.add(new PoseFrame(frames.size(), currentTimestamp,
                                new ArrayList<>(current.values())));
                    }
                    currentTimestamp = timeMs;
                    current = new TreeMap<>();
                }

                // Step4：將 landmark 放入 TreeMap，依 landmarkIndex 自動排序
                if (current != null) {
                    current.put(landmarkIndex, new Landmark(x, y, z, visibility, presence));
                }
            }

            // Step5：處理最後一幀
            if (current != null && currentTimestamp != Long.MIN_VALUE) {
                frames.add(new PoseFrame(frames.size(), currentTimestamp,
                        new ArrayList<>(current.values())));
            }
        }
        return new MotionSequence(MotionSource.VIDEO, 0f, frames);
    }

    /**
     * 將 MotionSequence 寫入 JSON 檔案。
     *
     * @param file     目標 JSON 檔案
     * @param sequence 要寫入的動作序列
     * @throws IOException 檔案寫入失敗時拋出
     */
    public static void writeJson(File file, MotionSequence sequence) throws IOException {
        try (FileWriter writer = new FileWriter(file)) {
            writer.write(toJsonString(sequence));
        }
    }

    /**
     * 將 MotionSequence 轉換為 JSON 字串。
     *
     * <p>使用自寫極簡 JSON 序列化，不依賴 Android org.json 或 Gson。
     *
     * @param sequence 要轉換的動作序列
     * @return JSON 格式的字串
     */
    public static String toJsonString(MotionSequence sequence) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\n");
        sb.append("  \"source\": ").append(quote(sequence.source == null ? "" : sequence.source.name())).append(",\n");
        sb.append("  \"fps\": ").append(sequence.fps).append(",\n");
        sb.append("  \"frames\": [\n");

        // Step1：逐幀序列化
        for (int i = 0; i < sequence.frames.size(); i++) {
            PoseFrame frame = sequence.frames.get(i);
            sb.append("    {\"frameIndex\": ").append(frame.frameIndex)
                    .append(", \"timestampMs\": ").append(frame.timestampMs)
                    .append(", \"landmarks\": [");

            // Step2：逐 landmark 序列化
            for (int j = 0; j < frame.landmarks.size(); j++) {
                Landmark lm = frame.landmarks.get(j);
                sb.append("{\"x\":").append(lm.x)
                        .append(",\"y\":").append(lm.y)
                        .append(",\"z\":").append(lm.z)
                        .append(",\"visibility\":").append(lm.visibility)
                        .append(",\"presence\":").append(lm.presence)
                        .append("}");
                if (j < frame.landmarks.size() - 1) {
                    sb.append(",");
                }
            }
            sb.append("]}");
            if (i < sequence.frames.size() - 1) {
                sb.append(",");
            }
            sb.append("\n");
        }

        sb.append("  ]\n");
        sb.append("}");
        return sb.toString();
    }

    /**
     * 從 JSON 檔案讀取並解析成 MotionSequence。
     *
     * @param file JSON 檔案
     * @return 解析後的 MotionSequence
     * @throws IOException 檔案讀取失敗時拋出
     */
    public static MotionSequence readJson(File file) throws IOException {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }
        }
        return fromJsonString(sb.toString());
    }

    /**
     * 將 JSON 字串解析成 MotionSequence。
     *
     * <p>使用自寫極簡 JSON 解析器，不依賴 Android org.json 或 Gson。
     * 若 source 欄位無法對應到已知的 MotionSource，回退為 VIDEO。
     *
     * @param json JSON 字串
     * @return 解析後的 MotionSequence
     */
    @SuppressWarnings("unchecked")
    public static MotionSequence fromJsonString(String json) {
        Map<String, Object> root = (Map<String, Object>) new JsonParser(json).parse();

        // Step1：解析 source 欄位（回退為 VIDEO）
        MotionSource source = MotionSource.VIDEO;
        Object sourceObj = root.get("source");
        if (sourceObj instanceof String) {
            try {
                source = MotionSource.valueOf((String) sourceObj);
            } catch (IllegalArgumentException ignored) {
                source = MotionSource.VIDEO;
            }
        }

        // Step2：解析 fps
        double fps = asDouble(root.get("fps"));

        // Step3：逐幀解析 landmarks
        List<PoseFrame> frames = new ArrayList<>();
        Object framesObj = root.get("frames");
        if (framesObj instanceof List) {
            for (Object frameObj : (List<Object>) framesObj) {
                if (!(frameObj instanceof Map)) {
                    continue;
                }
                Map<String, Object> frameMap = (Map<String, Object>) frameObj;
                int frameIndex = (int) asDouble(frameMap.get("frameIndex"));
                long timestampMs = (long) asDouble(frameMap.get("timestampMs"));

                List<Landmark> landmarks = new ArrayList<>();
                Object landmarksObj = frameMap.get("landmarks");
                if (landmarksObj instanceof List) {
                    for (Object lmObj : (List<Object>) landmarksObj) {
                        if (!(lmObj instanceof Map)) {
                            continue;
                        }
                        Map<String, Object> lmMap = (Map<String, Object>) lmObj;
                        landmarks.add(new Landmark(
                                (float) asDouble(lmMap.get("x")),
                                (float) asDouble(lmMap.get("y")),
                                (float) asDouble(lmMap.get("z")),
                                (float) asDouble(lmMap.get("visibility")),
                                (float) asDouble(lmMap.get("presence"))));
                    }
                }
                frames.add(new PoseFrame(frameIndex, timestampMs, landmarks));
            }
        }
        return new MotionSequence(source, (float) fps, frames);
    }

    /**
     * 將 Object 安全轉換為 double。
     *
     * @param value 任意 Object（Number 或其餘類型）
     * @return 若是 Number 回傳 double 值，否則回傳 0.0
     */
    private static double asDouble(Object value) {
        if (value instanceof Number) {
            return ((Number) value).doubleValue();
        }
        return 0.0;
    }

    /**
     * 將字串加上 JSON 格式的引號並處理轉义字元。
     *
     * @param text 原始字串
     * @return 加引號後的 JSON 字串
     */
    private static String quote(String text) {
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '"' || c == '\\') {
                sb.append('\\');
            }
            sb.append(c);
        }
        sb.append('"');
        return sb.toString();
    }

    /**
     * 極簡 JSON 解析器，只支援本專案用到的 object / array / string / number / bool / null。
     *
     * <p>純 Java 實作，用來讓 PoseIO 在沒有 org.json / Gson 的環境下也能運作。
     * package-private：同 package 的 {@link DanceScriptIO} 共用同一個解析器。
     */
    static final class JsonParser {
        private final String text;
        private int pos = 0;

        JsonParser(String text) {
            this.text = text;
        }

        /**
         * 解析整個 JSON 字串。
         *
         * @return 解析後的 Java 物件（Map、List、String、Number、Boolean 或 null）
         */
        Object parse() {
            skipWhitespace();
            return parseValue();
        }

        private void skipWhitespace() {
            while (pos < text.length() && Character.isWhitespace(text.charAt(pos))) {
                pos++;
            }
        }

        private Object parseValue() {
            skipWhitespace();
            char c = text.charAt(pos);
            if (c == '{') {
                return parseObject();
            }
            if (c == '[') {
                return parseArray();
            }
            if (c == '"') {
                return parseString();
            }
            if (c == 't' || c == 'f') {
                return parseBoolean();
            }
            if (c == 'n') {
                pos += 4;
                return null;
            }
            return parseNumber();
        }

        private Map<String, Object> parseObject() {
            Map<String, Object> map = new LinkedHashMap<>();
            pos++;
            skipWhitespace();
            if (peek() == '}') {
                pos++;
                return map;
            }
            while (true) {
                skipWhitespace();
                String key = parseString();
                skipWhitespace();
                pos++;
                Object value = parseValue();
                map.put(key, value);
                skipWhitespace();
                char c = text.charAt(pos);
                if (c == ',') {
                    pos++;
                    continue;
                }
                if (c == '}') {
                    pos++;
                    break;
                }
            }
            return map;
        }

        private List<Object> parseArray() {
            List<Object> list = new ArrayList<>();
            pos++;
            skipWhitespace();
            if (peek() == ']') {
                pos++;
                return list;
            }
            while (true) {
                list.add(parseValue());
                skipWhitespace();
                char c = text.charAt(pos);
                if (c == ',') {
                    pos++;
                    continue;
                }
                if (c == ']') {
                    pos++;
                    break;
                }
            }
            return list;
        }

        private String parseString() {
            pos++;
            StringBuilder sb = new StringBuilder();
            while (pos < text.length()) {
                char c = text.charAt(pos++);
                if (c == '"') {
                    break;
                }
                if (c == '\\') {
                    char escaped = text.charAt(pos++);
                    switch (escaped) {
                        case 'n': sb.append('\n'); break;
                        case 't': sb.append('\t'); break;
                        case 'r': sb.append('\r'); break;
                        case 'b': sb.append('\b'); break;
                        case 'f': sb.append('\f'); break;
                        case '/': sb.append('/'); break;
                        case '\\': sb.append('\\'); break;
                        case '"': sb.append('"'); break;
                        case 'u':
                            sb.append((char) Integer.parseInt(text.substring(pos, pos + 4), 16));
                            pos += 4;
                            break;
                        default: sb.append(escaped);
                    }
                } else {
                    sb.append(c);
                }
            }
            return sb.toString();
        }

        private Object parseNumber() {
            int start = pos;
            while (pos < text.length()) {
                char c = text.charAt(pos);
                if ("0123456789+-.eE".indexOf(c) >= 0) {
                    pos++;
                } else {
                    break;
                }
            }
            String number = text.substring(start, pos);
            if (number.indexOf('.') >= 0 || number.indexOf('e') >= 0 || number.indexOf('E') >= 0) {
                return Double.parseDouble(number);
            }
            try {
                return (long) Long.parseLong(number);
            } catch (NumberFormatException e) {
                return Double.parseDouble(number);
            }
        }

        private Boolean parseBoolean() {
            if (text.startsWith("true", pos)) {
                pos += 4;
                return Boolean.TRUE;
            }
            pos += 5;
            return Boolean.FALSE;
        }

        private char peek() {
            return pos < text.length() ? text.charAt(pos) : '\0';
        }
    }
}
