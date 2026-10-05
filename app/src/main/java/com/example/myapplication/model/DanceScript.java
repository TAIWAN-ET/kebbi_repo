package com.example.myapplication.model;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 一整支舞的「播放腳本」：依時間排好的馬達指令表。
 *
 * <p>用途：電腦端分析影片後輸出這份腳本，Kebbi 端只要照 {@link Step#timeMs}
 * 把 {@link Step#commands} 送給 ctlMotor 就能跳舞，不用在機器人上跑 MediaPipe。
 *
 * <p>誰會呼叫它：
 * {@code robot.DanceScriptBuilder} 產生它，
 * {@code io.DanceScriptIO} 負責 JSON 讀寫。
 *
 * <p>不負責什麼：不做序列化、不做映射、不認識 Nuwa SDK。
 *
 * <p>純 Java POJO，PC 與 Android 共用同一份定義，兩邊格式才不會漂移。
 */
public class DanceScript {

    /** 腳本格式版本；格式有不相容變更時遞增，播放端據此拒絕看不懂的檔案。 */
    public static final int FORMAT_VERSION = 1;

    /** 讀入檔案的版本（寫出時固定為 {@link #FORMAT_VERSION}）。 */
    public int version = FORMAT_VERSION;
    /** 舞蹈名稱（通常是來源影片檔名，不含副檔名）。 */
    public String name = "";
    /** 取樣間隔（毫秒），也就是相鄰兩步的標準時間差。 */
    public long frameIntervalMs;
    /** 整支舞長度（毫秒），播放端用來決定何時結束並歸位。 */
    public long durationMs;
    /** 分析時取樣到的幀數（含被略過的）。 */
    public int sourceFrameCount;
    /** 因遮擋 / 不可信而略過、維持上一個姿勢的幀數。 */
    public int skippedFrameCount;
    /** 依時間升冪排列的播放步驟。 */
    public List<Step> steps = new ArrayList<>();

    public DanceScript() {
    }

    /**
     * @param name            舞蹈名稱
     * @param frameIntervalMs 取樣間隔（毫秒）
     */
    public DanceScript(String name, long frameIntervalMs) {
        this.name = name;
        this.frameIntervalMs = frameIntervalMs;
    }

    /**
     * 所有步驟的指令總數。
     *
     * @return 指令數
     */
    public int getCommandCount() {
        int count = 0;
        for (Step step : steps) {
            count += step.commands.size();
        }
        return count;
    }

    /**
     * 回傳一行可讀摘要，給 Log / 命令列顯示。
     *
     * @return 摘要字串
     */
    public String describe() {
        return String.format(Locale.US,
                "DanceScript v%d \"%s\": %d steps, %d cmds, %.1fs, interval=%dms, frames=%d (skipped %d)",
                version, name, steps.size(), getCommandCount(), durationMs / 1000f,
                frameIntervalMs, sourceFrameCount, skippedFrameCount);
    }

    /**
     * 腳本中的一步：在 {@link #timeMs} 這個時間點同時送出的一組馬達指令。
     *
     * <p>只包含「這一步有變化的馬達」；沒列出的馬達維持上一個角度。
     */
    public static class Step {
        /** 從舞蹈開始算起的時間（毫秒）。 */
        public long timeMs;
        /** 這一幀推導出的姿勢事件（例：BOTH_HANDS_UP），僅供除錯 / 模擬器顯示。 */
        public String event = "";
        /** 同時送出的馬達指令。 */
        public List<RobotCommand> commands = new ArrayList<>();

        public Step() {
        }

        /**
         * @param timeMs   時間（毫秒）
         * @param event    姿勢事件（可為空字串）
         * @param commands 這一步的馬達指令
         */
        public Step(long timeMs, String event, List<RobotCommand> commands) {
            this.timeMs = timeMs;
            this.event = event == null ? "" : event;
            this.commands = commands;
        }
    }
}
