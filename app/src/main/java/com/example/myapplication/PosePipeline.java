package com.example.myapplication;

import java.io.File;
import java.io.IOException;
import java.util.List;

/**
 * 資料層的協調者（Pipeline）。MainActivity 只呼叫這一層，不直接碰 PoseIO。
 *
 * 目前提供 verify()：執行 CSV -> MotionSequence -> JSON -> MotionSequence 的 round-trip，
 * 並做比對嚴格的驗證（不只比 frameCount，還比 landmark 數量與抽查座標）。
 *
 * 未來 Camera / 評分 / 機器人控制都會建構在相同 MotionSequence 之上，風險因此被控制在最小。
 * 純 Java，不依賴 Android。
 */
public final class PosePipeline {

    private static final float COORD_TOLERANCE = 1e-4f;

    private PosePipeline() {
    }

    /**
     * 執行 round-trip 驗證並回傳可顯示的結果字串。
     */
    public static String verify(File csvFile, File jsonFile) {
        try {
            MotionSequence fromCsv = PoseIO.readCsv(csvFile);
            PoseIO.writeJson(jsonFile, fromCsv);
            MotionSequence fromJson = PoseIO.readJson(jsonFile);
            return verifyEquality(fromCsv, fromJson, jsonFile.getAbsolutePath());
        } catch (IOException e) {
            return "PoseFrame round-trip failed: " + e.getMessage();
        }
    }

    private static String verifyEquality(MotionSequence a, MotionSequence b, String jsonPath) {
        StringBuilder report = new StringBuilder();
        boolean allOk = true;

        int frameCountA = a.getFrameCount();
        int frameCountB = b.getFrameCount();
        boolean frameCountOk = frameCountA == frameCountB;
        allOk &= frameCountOk;

        int landmarkCountA = a.getLandmarkCount();
        int landmarkCountB = b.getLandmarkCount();
        boolean landmarkCountOk = landmarkCountA == landmarkCountB;
        allOk &= landmarkCountOk;

        boolean perFrameOk = true;
        int minFrames = Math.min(frameCountA, frameCountB);
        for (int i = 0; i < minFrames; i++) {
            if (a.frames.get(i).landmarks.size() != b.frames.get(i).landmarks.size()) {
                perFrameOk = false;
                break;
            }
        }
        allOk &= perFrameOk;

        boolean spotOk = true;
        if (minFrames > 0) {
            spotOk = coordinatesMatch(a, b, 0, PoseLandmark.NOSE)
                    && coordinatesMatch(a, b, 0, PoseLandmark.LEFT_SHOULDER)
                    && coordinatesMatch(a, b, 0, PoseLandmark.RIGHT_SHOULDER);
        }
        allOk &= spotOk;

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

    private static boolean coordinatesMatch(MotionSequence a, MotionSequence b, int frame, int landmarkId) {
        if (frame >= a.frames.size() || frame >= b.frames.size()) {
            return false;
        }
        List<Landmark> la = a.frames.get(frame).landmarks;
        List<Landmark> lb = b.frames.get(frame).landmarks;
        if (landmarkId >= la.size() || landmarkId >= lb.size()) {
            return false;
        }
        Landmark x = la.get(landmarkId);
        Landmark y = lb.get(landmarkId);
        return Math.abs(x.x - y.x) < COORD_TOLERANCE
                && Math.abs(x.y - y.y) < COORD_TOLERANCE
                && Math.abs(x.z - y.z) < COORD_TOLERANCE;
    }
}
