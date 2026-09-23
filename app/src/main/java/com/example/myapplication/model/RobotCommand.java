package com.example.myapplication.model;

import java.util.Locale;

/**
 * 單一馬達動作指令。
 *
 * <p>用途：由 {@link com.example.myapplication.robot.RobotMapper} 從 {@link PoseFeature} 產生，
 * 描述「哪個馬達要轉到幾度、用多快的速度轉」。
 * 這是純 Java POJO，不認識任何機器人 SDK。
 *
 * <p>誰會呼叫它：
 * {@link com.example.myapplication.robot.RobotMapper#map(PoseFeature)} 產生，
 * {@link com.example.myapplication.robot.RobotController} 執行。
 *
 * <p>不負責什麼：
 * 不連接馬達，不做任何 I/O，不計算人體特徵。
 *
 * <p><b>單位注意</b>：{@link #speedDegPerSec} 是「度/秒」的<b>速度</b>，不是移動時間。
 * 這是為了對齊 Nuwa 官方 API {@code ctlMotor(int motor, float degree, float speed)}
 * —— 第三個參數在 SDK 內部是 {@code setSpeedInDegreePerSec}。
 * 舊版把它當成「秒數」傳入 1.0f，等於命令馬達以每秒 1 度爬行，
 * 轉 60 度要 60 秒，導致馬達永遠到不了目標角度。
 */
public class RobotCommand {
    /** 馬達 id（對應 {@link com.example.myapplication.robot.RobotMotor} 常數）。 */
    public int motorId;
    /** 目標角度（度）。 */
    public float degree;
    /** 移動速度（度/秒），直接對應 NuwaRobotAPI.ctlMotor 的第三個參數。 */
    public float speedDegPerSec;

    /** 無參建構子。 */
    public RobotCommand() {
    }

    /**
     * @param motorId        馬達 id（{@link com.example.myapplication.robot.RobotMotor}）
     * @param degree         目標角度（度）
     * @param speedDegPerSec 移動速度（度/秒）
     */
    public RobotCommand(int motorId, float degree, float speedDegPerSec) {
        this.motorId = motorId;
        this.degree = degree;
        this.speedDegPerSec = speedDegPerSec;
    }

    /**
     * 回傳可讀描述。
     *
     * @return 格式如 "M8: 45 deg @ 60 deg/s"
     */
    public String describe() {
        return "M" + motorId + ": " + String.format(Locale.US, "%.0f", degree)
                + " deg @ " + String.format(Locale.US, "%.0f", speedDegPerSec) + " deg/s";
    }
}
