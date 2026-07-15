package com.example.myapplication;

import java.util.List;

/**
 * 單一影格的人體姿態。不論來源是影片還是 Camera，最後都只會產出 PoseFrame。
 *
 * - frameIndex：影格序號，Debug 時比 timestamp 直覺（Frame 183 vs 36780ms）。
 * - timestampMs：時間戳記，播放同步 / FPS / 插值都靠它。
 * - landmarks：該影格的 33 個 Landmark。
 *
 * 純 Java POJO，不依賴 Android。
 */
public class PoseFrame {
    public int frameIndex;
    public long timestampMs;
    public List<Landmark> landmarks;

    public PoseFrame() {
    }

    public PoseFrame(int frameIndex, long timestampMs, List<Landmark> landmarks) {
        this.frameIndex = frameIndex;
        this.timestampMs = timestampMs;
        this.landmarks = landmarks;
    }

    public Landmark getLandmark(int id) {
        return landmarks.get(id);
    }
}
