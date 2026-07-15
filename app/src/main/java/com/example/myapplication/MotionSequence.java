package com.example.myapplication;

import java.util.List;

/**
 * 一段完整的動作序列，也就是「數據化之後的舞蹈」。
 * 由多個 PoseFrame 組成；影片只解析一次就能產出它，之後播放/評分都直接用它。
 *
 * - source：來源（VIDEO / CAMERA / JSON）。
 * - fps：取樣幀率，影片可能 30、Camera 可能 15，播放器需要。
 * - frames：所有 PoseFrame。landmark 數量直接用 frames.get(0).landmarks.size() 取得，
 *   不再額外存 landmarkCount，避免兩邊資料不同步。
 *
 * 純 Java POJO，不依賴 Android。
 */
public class MotionSequence {
    public MotionSource source;
    public float fps;
    public List<PoseFrame> frames;

    public MotionSequence() {
    }

    public MotionSequence(MotionSource source, float fps, List<PoseFrame> frames) {
        this.source = source;
        this.fps = fps;
        this.frames = frames;
    }

    public int getFrameCount() {
        return frames == null ? 0 : frames.size();
    }

    public int getLandmarkCount() {
        if (frames == null || frames.isEmpty()) {
            return 0;
        }
        return frames.get(0).landmarks.size();
    }

    public String describe() {
        return "source=" + (source == null ? "?" : source.name())
                + " frames=" + getFrameCount()
                + " landmarkCount=" + getLandmarkCount()
                + " fps=" + fps;
    }
}
