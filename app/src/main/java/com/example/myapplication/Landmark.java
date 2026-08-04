package com.example.myapplication;

/**
 * 單一人體關節點。
 *
 * <p>用途：儲存單一關節點的三維座標與可見性資訊。MediaPipe、MoveNet、BlazePose、OpenPose
 * 等任何姿態辨識模型的輸出都可以統一用此結構表示。
 *
 * <p>誰會呼叫它：
 * {@link MainActivity#toPoseFrame(long, java.util.List)} 從 MediaPipe 原始輸出轉換時建立，
 * {@link PoseIO#readCsv(File)} 從 CSV 讀取時建立，
 * {@link PoseMath} 各個靜態方法計算時建立新的 Landmark 實例。
 *
 * <p>不負責什麼：
 * 不計算角度或距離，不處理檔案 I/O，不依賴 Android 或 MediaPipe 執行期。
 * 純粹是資料載體（POJO）。
 *
 * <p>注意：所有欄位皆為 mutable public field（非 final），方便 Gson 等 JSON 函式庫
 * 直接反序列化。
 */
public class Landmark {
    /** 畫面歸一化 X 座標（0.0 ~ 1.0）。 */
    public float x;
    /** 畫面歸一化 Y 座標（0.0 ~ 1.0）。 */
    public float y;
    /** 深度座標。 */
    public float z;
    /** 該關節點在畫面中可被看見的程度（0.0 ~ 1.0）。 */
    public float visibility;
    /** 該關節點存在於場景中的信心值（0.0 ~ 1.0）。 */
    public float presence;

    /** 無參建構子，供 JSON 反序列化使用。 */
    public Landmark() {
    }

    /**
     * 建立一個帶完整座標與可見性資訊的關節點。
     *
     * @param x          畫面歸一化 X 座標（0.0 ~ 1.0）
     * @param y          畫面歸一化 Y 座標（0.0 ~ 1.0）
     * @param z          深度座標
     * @param visibility 可見程度（0.0 ~ 1.0）
     * @param presence   存在信心值（0.0 ~ 1.0）
     */
    public Landmark(float x, float y, float z, float visibility, float presence) {
        this.x = x;
        this.y = y;
        this.z = z;
        this.visibility = visibility;
        this.presence = presence;
    }
}
