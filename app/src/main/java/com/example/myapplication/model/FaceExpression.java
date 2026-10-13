package com.example.myapplication.model;

/**
 * 從人臉辨識出的表情類別。
 *
 * <p>用途：{@code analysis.FaceExpressionAnalyzer} 的輸出，
 * {@code robot.KebbiFaceMap} 再把它對應到 Kebbi 的臉部動畫。
 *
 * <p>只收「舞蹈影片裡常見、Kebbi 臉上也表達得出來」的幾種；
 * 恐懼 / 厭惡在影片裡很少見、blendshape 也分不太出來，併入 {@link #NEUTRAL}。
 */
public enum FaceExpression {
    /** 沒有明顯表情，或分數都不夠高。 */
    NEUTRAL,
    /** 笑。 */
    HAPPY,
    /** 驚訝：眉毛上揚 + 張嘴。 */
    SURPRISED,
    /** 生氣：皺眉、眉毛下壓。 */
    ANGRY,
    /** 難過：嘴角下垂、眉頭上揚。 */
    SAD
}
