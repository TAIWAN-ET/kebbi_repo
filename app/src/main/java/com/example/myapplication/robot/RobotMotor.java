package com.example.myapplication.robot;

/**
 * 機器人馬達索引常數。
 *
 * <p>用途：讓 {@link com.example.myapplication.model.RobotCommand} 與 {@link RobotMapper} 用專案自訂的馬達 id，
 * 不必在純 Java 層直接依賴 Nuwa SDK（數值與 {@code NuwaRobotAPI.MOTOR_*} 一致，
 * 由 {@link RobotController} 在送指令時對應）。
 *
 * <p>誰會呼叫它：
 * {@link RobotMapper} 產生 {@link com.example.myapplication.model.RobotCommand} 時使用，
 * {@link RobotController} 送 ctlMotor 指令時對應。
 *
 * <p>不負責什麼：
 * 不認識任何 SDK，純 Java 常數定義。
 *
 * <p>數值來源：Nuwa 官方 JavaDoc {@code NuwaRobotAPI} 的 {@code MOTOR_*} 常數
 * （NECK_Y=1、NECK_Z=2、RIGHT_SHOULDER_Z=3 ... LEFT_ELBOW_Y=10）。
 */
public final class RobotMotor {

    private RobotMotor() {
    }

    // ---------------------------------------------------------------
    // 硬體 id（與 NuwaRobotAPI.MOTOR_* 數值一致，不要改動）
    // ---------------------------------------------------------------

    /** 頸部 Y 軸馬達（NuwaRobotAPI.MOTOR_NECK_Y）。 */
    public static final int NECK_Y = 1;
    /** 頸部 Z 軸馬達（NuwaRobotAPI.MOTOR_NECK_Z）。 */
    public static final int NECK_Z = 2;
    // 方向與範圍（度，SDK 角度表 / robot.xml / 官方動作檔）：
    //   SHOULDER_Y -200~70，負＝往前上舉（-180 舉過頭）
    //   SHOULDER_X -3~100，側向外展
    //   SHOULDER_Z -85~5，繞垂直軸水平擺，負＝往胸前收
    //   ELBOW_Y    -80~0，負＝彎肘

    /** 右肩 Z（水平前後擺）。 */
    public static final int RIGHT_SHOULDER_Z = 3;
    /** 右肩 Y（舉/放，負＝上舉）。 */
    public static final int RIGHT_SHOULDER_Y = 4;
    /** 右肩 X（側向外展）。 */
    public static final int RIGHT_SHOULDER_X = 5;
    /** 右肘 Y（彎/伸，負＝彎）。 */
    public static final int RIGHT_ELBOW_Y = 6;
    /** 左肩 Z（水平前後擺）。 */
    public static final int LEFT_SHOULDER_Z = 7;
    /** 左肩 Y（舉/放，負＝上舉）。 */
    public static final int LEFT_SHOULDER_Y = 8;
    /** 左肩 X（側向外展）。 */
    public static final int LEFT_SHOULDER_X = 9;
    /** 左肘 Y（彎/伸，負＝彎）。 */
    public static final int LEFT_ELBOW_Y = 10;

    // ---------------------------------------------------------------
    // 語意別名（RobotMapper 只用這兩個，不直接用 NECK_Y / NECK_Z）
    // ---------------------------------------------------------------

    /**
     * 頭部「左右轉」對應的馬達：NECK_Z（繞垂直軸），正值＝往機器人自己的左邊轉。
     *
     * <p>依據（官方資料，三者一致）：
     * <ul>
     *   <li>NUWA 網頁模擬器的 robot.xml：neck_z 的轉軸是 (0 0 1)，neck_y 是 (0 1 0)</li>
     *   <li>官方動作 666_TA_LookLR（先看左再看右）：neck_z 先 +21° 再 -18°；neck_y 幾乎不動</li>
     *   <li>SDK 角度表：NECK_Z 範圍 ±31°（編碼器），NECK_Y ±20°</li>
     * </ul>
     * 仍建議用 Test Head Limits 在實機上看一次。
     */
    public static final int NECK_YAW = NECK_Z;

    /**
     * 頭部「上下點」對應的馬達：NECK_Y，正值＝低頭。
     * 依據：官方動作 666_TA_LookDnU（先低頭再抬頭）neck_y 先 +9° 再 -8°。
     */
    public static final int NECK_PITCH = NECK_Y;
}
