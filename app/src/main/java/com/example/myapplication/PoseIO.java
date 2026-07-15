package com.example.myapplication;

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
 * MainActivity 永遠只呼叫 PoseIO，不自己處理序列化細節。
 *
 * 使用 Android 內建套件以外的「純 Java」實作（自寫極簡 JSON 解析），
 * 因此本類別不依賴 Android，PC Java / 單元測試也能直接重用。
 *
 * 提供的轉換（構成 P0-A round-trip）：
 *   readCsv(File)   : CSV  -> MotionSequence
 *   writeCsv(File)  : MotionSequence -> CSV
 *   readJson(File)  : JSON -> MotionSequence
 *   writeJson(File) : MotionSequence -> JSON
 */
public final class PoseIO {

    private PoseIO() {
    }

    public static void writeCsv(File file, MotionSequence sequence) throws IOException {
        try (FileWriter writer = new FileWriter(file)) {
            writer.write("time_ms,pose_index,landmark_index,x,y,z,visibility,presence\n");
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
     * 讀取 CSV 並重組成 MotionSequence。
     * 使用 TreeMap 依 landmarkIndex 定位，即使 CSV 順序錯亂（如 0,2,1,3）也能正確排回，
     * 不會產生錯位的 Landmark。
     */
    public static MotionSequence readCsv(File file) throws IOException {
        List<PoseFrame> frames = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            String line = reader.readLine(); // 跳過 header
            if (line == null) {
                return new MotionSequence(MotionSource.VIDEO, 0f, frames);
            }

            long currentTimestamp = Long.MIN_VALUE;
            TreeMap<Integer, Landmark> current = null;

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

                if (timeMs != currentTimestamp) {
                    if (current != null && currentTimestamp != Long.MIN_VALUE) {
                        frames.add(new PoseFrame(frames.size(), currentTimestamp,
                                new ArrayList<>(current.values())));
                    }
                    currentTimestamp = timeMs;
                    current = new TreeMap<>();
                }
                if (current != null) {
                    current.put(landmarkIndex, new Landmark(x, y, z, visibility, presence));
                }
            }
            if (current != null && currentTimestamp != Long.MIN_VALUE) {
                frames.add(new PoseFrame(frames.size(), currentTimestamp,
                        new ArrayList<>(current.values())));
            }
        }
        return new MotionSequence(MotionSource.VIDEO, 0f, frames);
    }

    public static void writeJson(File file, MotionSequence sequence) throws IOException {
        try (FileWriter writer = new FileWriter(file)) {
            writer.write(toJsonString(sequence));
        }
    }

    public static String toJsonString(MotionSequence sequence) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\n");
        sb.append("  \"source\": ").append(quote(sequence.source == null ? "" : sequence.source.name())).append(",\n");
        sb.append("  \"fps\": ").append(sequence.fps).append(",\n");
        sb.append("  \"frames\": [\n");
        for (int i = 0; i < sequence.frames.size(); i++) {
            PoseFrame frame = sequence.frames.get(i);
            sb.append("    {\"frameIndex\": ").append(frame.frameIndex)
                    .append(", \"timestampMs\": ").append(frame.timestampMs)
                    .append(", \"landmarks\": [");
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

    @SuppressWarnings("unchecked")
    public static MotionSequence fromJsonString(String json) {
        Map<String, Object> root = (Map<String, Object>) new JsonParser(json).parse();

        MotionSource source = MotionSource.VIDEO;
        Object sourceObj = root.get("source");
        if (sourceObj instanceof String) {
            try {
                source = MotionSource.valueOf((String) sourceObj);
            } catch (IllegalArgumentException ignored) {
                source = MotionSource.VIDEO;
            }
        }

        double fps = asDouble(root.get("fps"));

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

    private static double asDouble(Object value) {
        if (value instanceof Number) {
            return ((Number) value).doubleValue();
        }
        return 0.0;
    }

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
     * 純 Java 實作，用來讓 PoseIO 在沒有 org.json / Gson 的環境下也能運作。
     */
    private static final class JsonParser {
        private final String text;
        private int pos = 0;

        JsonParser(String text) {
            this.text = text;
        }

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
