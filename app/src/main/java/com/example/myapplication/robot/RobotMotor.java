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
    /** 右肩 Z（外展）。 */
    public static final int RIGHT_SHOULDER_Z = 3;
    /** 右肩 Y（舉/放）。 */
    public static final int RIGHT_SHOULDER_Y = 4;
    /** 右肩 X（前/後擺）。 */
    public static final int RIGHT_SHOULDER_X = 5;
    /** 右肘 Y（彎/伸）。 */
    public static final int RIGHT_ELBOW_Y = 6;
    /** 左肩 Z（外展）。 */
    public static final int LEFT_SHOULDER_Z = 7;
    /** 左肩 Y（舉/放）。 */
    public static final int LEFT_SHOULDER_Y = 8;
    /** 左肩 X（前/後擺）。 */
    public static final int LEFT_SHOULDER_X = 9;
    /** 左肘 Y（彎/伸）。 */
    public static final int LEFT_ELBOW_Y = 10;

    // ---------------------------------------------------------------
    // 語意別名（RobotMapper 只用這兩個，不直接用 NECK_Y / NECK_Z）
    // ---------------------------------------------------------------

    /**
     * 頭部「左右轉」對應的馬達。
     *
     * <p><b>TODO 實機確認</b>：官方 JavaDoc 對 {@code MOTOR_NECK_Y} / {@code MOTOR_NECK_Z}
     * 只寫 "Motor neck y" / "Motor neck z"，沒有說明哪個是 yaw、哪個是 pitch。
     * 機器人學慣例是 Z 軸垂直向上、繞 Z 轉才是左右轉，
     * 所以目前這組對應<b>有可能是反的</b>。
     *
     * <p>驗證方法：單獨下 {@code ctlMotor(RobotMotor.NECK_Z, 30f, 30f)}，
     * 看頭是左右轉還是上下點。若發現相反，只要把這兩行的 NECK_Y / NECK_Z 對調即可，
     * 其餘程式碼完全不用動。
     */
    public static final int NECK_YAW = NECK_Y;

    /**
     * 頭部「上下點」對應的馬達。詳見 {@link #NECK_YAW} 的實機確認說明。
     */
    public static final int NECK_PITCH = NECK_Z;
}
