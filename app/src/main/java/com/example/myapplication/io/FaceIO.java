package com.example.myapplication.io;

import com.example.myapplication.model.FaceFrame;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 讀 {@code pc/extract_face.py} 輸出的 face_blendshapes.csv。
 *
 * <p>CSV 格式（欄位依名稱讀取，順序不限）：
 * <pre>
 * time_ms,detected,source,_neutral,browDownLeft,...,mouthSmileLeft,...
 * 0,1,head,0.0001,0.0312,...
 * 200,0,,,,...
 * </pre>
 * 評估用的 CSV 可以多一欄 {@code label}（人工標註），沒有 time_ms 時以行號當時間。
 *
 * <p>不負責什麼：不判斷表情、不寫檔。純 Java，PC 與 Android 共用。
 */
public final class FaceIO {

    private static final String COL_TIME = "time_ms";
    private static final String COL_DETECTED = "detected";
    private static final String COL_SOURCE = "source";
    private static final String COL_LABEL = "label";

    private FaceIO() {
    }

    /**
     * @param file face_blendshapes.csv
     * @return 依檔案順序排列的臉部幀
     * @throws IOException 讀取失敗或沒有標頭時拋出
     */
    public static List<FaceFrame> readCsv(File file) throws IOException {
        List<FaceFrame> frames = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {
            String headerLine = reader.readLine();
            if (headerLine == null) {
                throw new IOException("empty face CSV: " + file);
            }
            String[] header = headerLine.trim().split(",", -1);

            String line;
            int row = 0;
            while ((line = reader.readLine()) != null) {
                if (line.trim().isEmpty()) {
                    continue;
                }
                String[] cells = line.split(",", -1);
                FaceFrame frame = new FaceFrame();
                frame.timestampMs = row++;
                // 沒有 detected 欄（例如評估用 CSV）就看有沒有分數
                boolean hasDetectedColumn = false;
                for (int i = 0; i < header.length && i < cells.length; i++) {
                    String name = header[i];
                    String cell = cells[i].trim();
                    if (COL_TIME.equals(name)) {
                        frame.timestampMs = (long) Double.parseDouble(cell);
                    } else if (COL_DETECTED.equals(name)) {
                        hasDetectedColumn = true;
                        frame.detected = "1".equals(cell) || "true".equalsIgnoreCase(cell);
                    } else if (COL_LABEL.equals(name)) {
                        frame.label = cell;
                    } else if (!COL_SOURCE.equals(name) && !cell.isEmpty()) {
                        try {
                            frame.blendshapes.put(name, Float.parseFloat(cell));
                        } catch (NumberFormatException ignored) {
                            // 非數值欄位（例如圖片檔名）略過
                        }
                    }
                }
                if (!hasDetectedColumn) {
                    frame.detected = !frame.blendshapes.isEmpty();
                }
                if (!frame.detected) {
                    frame.blendshapes.clear();
                }
                frames.add(frame);
            }
        }
        return frames;
    }
}
