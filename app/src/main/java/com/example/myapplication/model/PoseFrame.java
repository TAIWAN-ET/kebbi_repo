package com.example.myapplication.model;

import com.example.myapplication.MainActivity;
import com.example.myapplication.analysis.PoseAnalyzer;
import com.example.myapplication.io.PoseIO;
import com.example.myapplication.math.PoseMath;
import java.util.List;

/**
 * 單一影格的人體姿態。
 *
 * <p>用途：無論動作來源是影片、攝影機或 JSON，經 MediaPipe 解析後都會產出
 * 一個 {@link PoseFrame}，其中包含該影格的 33 個 {@link Landmark}。
 * 後續的 {@link PoseAnalyzer}、{@link PoseMath}、{@link PoseIO} 都以此為輸入。
 *
 * <p>誰會呼叫它：
 * {@link MainActivity} 從 MediaPipe 原始輸出轉換時建立，
 * {@link PoseIO} 從 CSV 讀取時建立，
 * {@link PoseIO} 從 JSON 讀取時建立，
 * {@link PoseAnalyzer#analyzeFrame(PoseFrame)} 分析姿態特徵時讀取。
 *
 * <p>不負責什麼：
 * 不解析任何檔案格式，不計算任何角度或距離，不處理 Android UI 或 MediaPipe 執行期。
 * 純粹是資料載體（POJO）。
 *
 * <p>欄位說明：
 * - frameIndex：影格序號，Debug 時比 timestamp 直覺（Frame 183 vs 36780ms）。
 * - timestampMs：時間戳記（毫秒），播放同步 / FPS / 插值都靠它。
 * - landmarks：該影格的 33 個 {@link Landmark}（MediaPipe normalized，影像座標 0~1）。
 * - worldLandmarks：同 33 點的 MediaPipe world landmark（公尺，原點在髖中心），可為 null。
 *
 * <p><b>為什麼要兩份座標</b>：normalized landmark 的 x 是除以「影像寬」、y 是除以「影像高」，
 * 兩個軸的分母不同。1920x1080 的影片會把 x 壓成 y 的 56%，
 * 拿它去算 acos 夾角，算出來的角度會隨影片長寬比而變形。
 * MediaPipe 另外提供 world landmark（公制、原點在髖中心）就是為了這個用途，
 * 所以「角度」一律用 {@link #getAngleLandmarks()}，
 * 「誰比誰高 / 誰比誰左」這種畫面上的相對位置才用 {@link #landmarks}。
 */
public class PoseFrame {
    /** 影格序號，從 0 開始遞增。 */
    public int frameIndex;
    /** 時間戳記（毫秒）。 */
    public long timestampMs;
    /** 該影格的 33 個關節點列表（normalized 影像座標，0~1）。 */
    public List<Landmark> landmarks;
    /**
     * 該影格的 33 個 world landmark（公尺，原點在髖中心）。
     *
     * <p>從 CSV / JSON 還原的 PoseFrame 沒有這份資料，會是 null，
     * 此時 {@link #getAngleLandmarks()} 會退回 {@link #landmarks}。
     */
    public List<Landmark> worldLandmarks;

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
     * 建立一個同時帶影像座標與公制座標的姿態影格。
     *
     * @param frameIndex     影格序號（從 0 開始）
     * @param timestampMs    時間戳記（毫秒）
     * @param landmarks      normalized landmark（影像座標）
     * @param worldLandmarks world landmark（公尺）；沒有時可傳 null
     */
    public PoseFrame(int frameIndex, long timestampMs, List<Landmark> landmarks,
                     List<Landmark> worldLandmarks) {
        this.frameIndex = frameIndex;
        this.timestampMs = timestampMs;
        this.landmarks = landmarks;
        this.worldLandmarks = worldLandmarks;
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

    /**
     * 取得「拿來算角度」的那份座標。
     *
     * <p>有 world landmark 就用它（公制、不受影片長寬比影響）；
     * 沒有（例如從 CSV 還原）才退回 normalized，維持向後相容。
     *
     * @return 用於角度計算的 33 點列表
     */
    public List<Landmark> getAngleLandmarks() {
        if (worldLandmarks != null && worldLandmarks.size() >= PoseLandmark.COUNT) {
            return worldLandmarks;
        }
        return landmarks;
    }
}
