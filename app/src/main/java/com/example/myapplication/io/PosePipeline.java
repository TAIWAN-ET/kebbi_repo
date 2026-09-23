package com.example.myapplication.io;

import com.example.myapplication.MainActivity;
import com.example.myapplication.math.PoseMath;
import com.example.myapplication.model.Landmark;
import com.example.myapplication.model.MotionSequence;
import com.example.myapplication.model.PoseFrame;
import com.example.myapplication.model.PoseLandmark;

import java.io.File;
import java.io.IOException;
import java.util.List;

/**
 * 資料層的協調者（Pipeline）。
 *
 * <p>用途：提供 round-trip 驗證功能，確保 CSV → MotionSequence → JSON → MotionSequence
 * 的資料完整性。MainActivity 只呼叫這一層，不直接碰 PoseIO。
 *
 * <p>誰會呼叫它：
 * {@link MainActivity} 在分析影片後呼叫 verify()
 * 確認 CSV 與 JSON 資料一致，
 * 或在做離線分析時呼叫。
 *
 * <p>不負責什麼：
 * 不解析任何檔案格式（由 {@link PoseIO} 負責），不計算任何角度或距離，
 * 不處理 Android UI 或 MediaPipe 執行期，不認識任何機器人 SDK。
 * 純 Java，不依賴 Android。
 */
public final class PosePipeline {

    /** 座標比對容差（浮點數誤差範圍）。 */
    private static final float COORD_TOLERANCE = 1e-4f;

    private PosePipeline() {
    }

    /**
     * 執行 round-trip 驗證並回傳可顯示的結果字串。
     *
     * <p>流程：CSV → MotionSequence → JSON → MotionSequence，
     * 然後比對 frameCount、landmarkCount、每幀 landmark 數量、
     * 以及 frame0 的 nose / 左肩 / 右肩座標。
     *
     * @param csvFile  來源 CSV 檔案
     * @param jsonFile 目標 JSON 檔案（會被寫入）
     * @return 驗證結果字串，包含每項比對的 OK/FAIL 狀態
     */
    public static String verify(File csvFile, File jsonFile) {
        try {
            // Step1：從 CSV 讀取 MotionSequence
            MotionSequence fromCsv = PoseIO.readCsv(csvFile);

            // Step2：寫入 JSON
            PoseIO.writeJson(jsonFile, fromCsv);

            // Step3：從 JSON 讀回 MotionSequence
            MotionSequence fromJson = PoseIO.readJson(jsonFile);

            // Step4：比對兩份 MotionSequence
            return verifyEquality(fromCsv, fromJson, jsonFile.getAbsolutePath());
        } catch (IOException e) {
            return "PoseFrame round-trip failed: " + e.getMessage();
        }
    }

    /**
     * 比對兩份 MotionSequence 的完整度，產出驗證報告。
     *
     * <p>Step1：比對 frameCount。
     * Step2：比對 landmarkCount。
     * Step3：比對每幀的 landmark 數量。
     * Step4：抽查 frame0 的 nose / 左肩 / 右肩座標。
     *
     * @param a        第一份 MotionSequence（來自 CSV）
     * @param b        第二份 MotionSequence（來自 JSON）
     * @param jsonPath JSON 檔案路徑（用於報告）
     * @return 可顯示的驗證報告字串
     */
    private static String verifyEquality(MotionSequence a, MotionSequence b, String jsonPath) {
        StringBuilder report = new StringBuilder();
        boolean allOk = true;

        // Step1：比對 frameCount
        int frameCountA = a.getFrameCount();
        int frameCountB = b.getFrameCount();
        boolean frameCountOk = frameCountA == frameCountB;
        allOk &= frameCountOk;

        // Step2：比對 landmarkCount
        int landmarkCountA = a.getLandmarkCount();
        int landmarkCountB = b.getLandmarkCount();
        boolean landmarkCountOk = landmarkCountA == landmarkCountB;
        allOk &= landmarkCountOk;

        // Step3：比對每幀的 landmark 數量
        boolean perFrameOk = true;
        int minFrames = Math.min(frameCountA, frameCountB);
        for (int i = 0; i < minFrames; i++) {
            if (a.frames.get(i).landmarks.size() != b.frames.get(i).landmarks.size()) {
                perFrameOk = false;
                break;
            }
        }
        allOk &= perFrameOk;

        // Step4：抽查 frame0 的 nose / 左肩 / 右肩座標
        boolean spotOk = true;
        if (minFrames > 0) {
            spotOk = coordinatesMatch(a, b, 0, PoseLandmark.NOSE)
                    && coordinatesMatch(a, b, 0, PoseLandmark.LEFT_SHOULDER)
                    && coordinatesMatch(a, b, 0, PoseLandmark.RIGHT_SHOULDER);
        }
        allOk &= spotOk;

        // Step5：組合報告字串
        report.append("PoseFrame round-trip: ").append(allOk ? "OK" : "MISMATCH").append('\n');
        report.append("  frameCount: ").append(frameCountA).append(" vs ").append(frameCountB)
                .append(frameCountOk ? "  OK" : "  FAIL").append('\n');
        report.append("  landmarkCount: ").append(landmarkCountA).append(" vs ").append(landmarkCountB)
                .append(landmarkCountOk ? "  OK" : "  FAIL").append('\n');
        report.append("  per-frame landmark count: ")
                .append(perFrameOk ? "OK" : "FAIL").append('\n');
        report.append("  spot-check frame0 (nose/L-shoulder/R-shoulder) coords: ")
                .append(spotOk ? "OK" : "FAIL").append('\n');
        report.append("JSON: ").append(jsonPath);
        return report.toString();
    }

    /**
     * 比對兩份 MotionSequence 中同一幀同一 landmark 的座標。
     *
     * @param a           第一份 MotionSequence
     * @param b           第二份 MotionSequence
     * @param frame       影格索引
     * @param landmarkId  Landmark 編號（{@link PoseLandmark} 中的常數）
     * @return 若座標差異小於 {@link #COORD_TOLERANCE}，回傳 true
     */
    private static boolean coordinatesMatch(MotionSequence a, MotionSequence b, int frame, int landmarkId) {
        // Step1：檢查影格索引是否越界
        if (frame >= a.frames.size() || frame >= b.frames.size()) {
            return false;
        }
        List<Landmark> la = a.frames.get(frame).landmarks;
        List<Landmark> lb = b.frames.get(frame).landmarks;

        // Step2：檢查 landmark 索引是否越界
        if (landmarkId >= la.size() || landmarkId >= lb.size()) {
            return false;
        }

        // Step3：比對 x, y, z 三座標
        Landmark x = la.get(landmarkId);
        Landmark y = lb.get(landmarkId);
        return Math.abs(x.x - y.x) < COORD_TOLERANCE
                && Math.abs(x.y - y.y) < COORD_TOLERANCE
                && Math.abs(x.z - y.z) < COORD_TOLERANCE;
    }
}
