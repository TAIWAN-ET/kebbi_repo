package com.example.myapplication;

import java.util.List;

/**
 * 一段完整的動作序列，也就是「數據化之後的舞蹈」。
 *
 * <p>用途：由多個 {@link PoseFrame} 組成；影片只解析一次就能產出它，
 * 之後的播放、評分、機器人控制全部共用同一份 {@link MotionSequence}，
 * 不用為影片和即時影像做兩套邏輯。
 *
 * <p>誰會呼叫它：
 * {@link PoseIO} 從 CSV 讀取時建立，
 * {@link PoseIO} 從 JSON 讀取時建立，
 * {@link MainActivity} 從 MediaPipe 逐幀匯集時建立，
 * {@link PosePipeline} 做 round-trip 驗證時讀取。
 *
 * <p>不負責什麼：
 * 不解析任何檔案格式，不計算任何角度或距離，不處理 Android UI 或 MediaPipe 執行期。
 * 純粹是資料載體（POJO）。
 */
public class MotionSequence {
    /** 動作序列來源（VIDEO / CAMERA / JSON）。 */
    public MotionSource source;
    /** 取樣幀率（影片可能 30、Camera 可能 15）。 */
    public float fps;
    /** 所有姿態影格列表。 */
    public List<PoseFrame> frames;

    /** 無參建構子，供 JSON 反序列化使用。 */
    public MotionSequence() {
    }

    /**
     * 建立一個完整的動作序列。
     *
     * @param source 動作序列來源（{@link MotionSource}）
     * @param fps    取樣幀率
     * @param frames 所有姿態影格列表
     */
    public MotionSequence(MotionSource source, float fps, List<PoseFrame> frames) {
        this.source = source;
        this.fps = fps;
        this.frames = frames;
    }

    /**
     * 取得影格總數。
     *
     * @return 影格數量；frames 為 null 時回傳 0
     */
    public int getFrameCount() {
        return frames == null ? 0 : frames.size();
    }

    /**
     * 取得每個影格的 Landmark 數量（應為 33）。
     *
     * @return Landmark 數量；frames 為空或 null 時回傳 0
     */
    public int getLandmarkCount() {
        if (frames == null || frames.isEmpty()) {
            return 0;
        }
        return frames.get(0).landmarks.size();
    }

    /**
     * 回傳此序列的簡短描述字串。
     *
     * @return 格式如 "source=VIDEO frames=120 landmarkCount=33 fps=5.0"
     */
    public String describe() {
        return "source=" + (source == null ? "?" : source.name())
                + " frames=" + getFrameCount()
                + " landmarkCount=" + getLandmarkCount()
                + " fps=" + fps;
    }
}
