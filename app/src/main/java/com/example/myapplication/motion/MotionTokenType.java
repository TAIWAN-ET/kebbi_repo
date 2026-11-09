package com.example.myapplication.motion;

/**
 * 動作原子的類型（動作庫的「字母」）。
 *
 * <p>每一種 token 都代表一段可用參數描述的動作，而不是逐幀座標。
 * 例：手在 1 秒內轉一圈 = 一個 {@link #CIRCLE} token（半徑、方向、圈數、週期）。
 */
public enum MotionTokenType {
    /** 靜止或停頓。 */
    HOLD("HOLD"),
    /** 2D 封閉圓形軌跡（可多圈）。 */
    CIRCLE("CIRCLE"),
    /** 直線來回擺盪（非圓）。 */
    SWING("SWING"),
    /** 手高於肩的來回擺盪（揮手）。 */
    WAVE("WAVE"),
    /** 往單一方向抬起。 */
    RAISE("RAISE"),
    /** 往單一方向放下。 */
    LOWER("LOWER"),
    /** 頭／軀幹往左轉。 */
    TURN_LEFT("TURN_L"),
    /** 頭／軀幹往右轉。 */
    TURN_RIGHT("TURN_R"),
    /** 頭部上下點動。 */
    NOD("NOD"),
    /** 頭部左右搖動。 */
    SHAKE("SHAKE"),
    /** 軀幹向左傾。 */
    LEAN_LEFT("LEAN_L"),
    /** 軀幹向右傾。 */
    LEAN_RIGHT("LEAN_R"),
    /** 無法辨識但必須保留的稀疏關鍵幀。 */
    FREEFORM("FREEFORM"),
    /** 未分類（保留給未來擴充）。 */
    UNKNOWN("UNKNOWN");

    private final String code;

    MotionTokenType(String code) {
        this.code = code;
    }

    /**
     * 取得短代碼（寫入 MotionScript 用）。
     *
     * @return 短代碼
     */
    public String code() {
        return code;
    }

    /**
     * 由短代碼找回類型。
     *
     * @param code 短代碼
     * @return 對應類型；找不到時回傳 {@link #UNKNOWN}
     */
    public static MotionTokenType fromCode(String code) {
        if (code != null) {
            for (MotionTokenType type : values()) {
                if (type.code.equalsIgnoreCase(code) || type.name().equalsIgnoreCase(code)) {
                    return type;
                }
            }
        }
        return UNKNOWN;
    }
}
