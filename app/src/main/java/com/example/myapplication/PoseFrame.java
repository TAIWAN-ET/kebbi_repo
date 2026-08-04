package com.example.myapplication;

import java.util.List;

/**
 * 單一影格的人體姿態。
 *
 * <p>用途：無論動作來源是影片、攝影機或 JSON，經 MediaPipe 解析後都會產出
 * 一個 {@link PoseFrame}，其中包含該影格的 33 個 {@link Landmark}。
 * 後續的 {@link PoseAnalyzer}、{@link PoseMath}、{@link PoseIO} 都以此為輸入。
 *
 * <p>誰會呼叫它：
 * {@link MainActivity#toPoseFrame(long, java.util.List)} 從 MediaPipe 原始輸出轉換時建立，
 * {@link PoseIO#readCsv(File)} 從 CSV 讀取時建立，
 * {@link PoseIO#readJson(File)} 從 JSON 讀取時建立，
 * {@link PoseAnalyzer#analyzeFrame(PoseFrame)} 分析姿態特徵時讀取。
 *
 * <p>不負責什麼：
 * 不解析任何檔案格式，不計算任何角度或距離，不處理 Android UI 或 MediaPipe 執行期。
 * 純粹是資料載體（POJO）。
 *
 * <p>欄位說明：
 * - frameIndex：影格序號，Debug 時比 timestamp 直覺（Frame 183 vs 36780ms）。
 * - timestampMs：時間戳記（毫秒），播放同步 / FPS / 插值都靠它。
 * - landmarks：該影格的 33 個 {@link Landmark}。
 */
public class PoseFrame {
    /** 影格序號，從 0 開始遞增。 */
    public int frameIndex;
    /** 時間戳記（毫秒）。 */
    public long timestampMs;
    /** 該影格的 33 個關節點列表。 */
    public List<Landmark> landmarks;

    /** 無參建構子，供 JSON 反序列化使用。 */
    public PoseFrame() {
    }

    /**
     * 建立一個帶完整資料的姿態影格。
     *
     * @param frameIndex 影格序號（從 0 開始）
     * @param timestampMs 時間戳記（毫秒）
     * @param landmarks 該影格的 33 個 {@link Landmark} 列表
     */
    public PoseFrame(int frameIndex, long timestampMs, List<Landmark> landmarks) {
        this.frameIndex = frameIndex;
        this.timestampMs = timestampMs;
        this.landmarks = landmarks;
    }

    /**
     * 取得指定編號的關節點。
     *
     * @param id {@link PoseLandmark} 中定義的關節點編號（0 ~ 32）
     * @return 對應的 {@link Landmark}
     */
    public Landmark getLandmark(int id) {
        return landmarks.get(id);
    }
}
