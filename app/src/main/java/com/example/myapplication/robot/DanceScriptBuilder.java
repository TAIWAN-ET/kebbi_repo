package com.example.myapplication.robot;

import com.example.myapplication.analysis.PoseAnalyzer;
import com.example.myapplication.model.DanceScript;
import com.example.myapplication.model.PoseFeature;
import com.example.myapplication.model.PoseFrame;
import com.example.myapplication.model.RobotCommand;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 把一整段 PoseFrame 序列轉成 {@link DanceScript}（依時間排好的馬達指令表）。
 *
 * <p>用途：PC Offload 的核心。電腦分析完影片後呼叫這裡，
 * 整條 PoseAnalyzer → RobotMapper 管線與 App 內完全相同，只是輸出變成一份可以傳給 Kebbi 的腳本。
 *
 * <p>誰會呼叫它：PC 端 {@code pc.DanceScriptGenerator}；日後 App 內分析影片也可以直接用它產生腳本。
 *
 * <p>不負責什麼：不做 MediaPipe、不做平滑（輸入的 PoseFrame 應已平滑），
 * 不做 I/O，不呼叫 Nuwa SDK。
 *
 * <p>和逐幀直接 {@link RobotMapper#map} 的差別：
 * <ul>
 *   <li><b>速度對齊時間</b>：RobotMapper 固定給 60 度/秒。
 *       腳本裡改成「在下一個取樣點之前剛好轉到」的速度（角度差 ÷ 取樣間隔），
 *       大動作不會追不上、小動作不會衝過頭，播放才對得上音樂。</li>
 *   <li><b>死區</b>：變化小於 {@link #DEAD_BAND_DEG} 的馬達不送，
 *       過濾掉殘餘抖動，也讓腳本小很多。</li>
 *   <li><b>遮擋幀</b>：{@link PoseFeature#confident} 為 false 的幀整步略過，
 *       機器人維持上一個姿勢，而不是跟著 MediaPipe 的猜測亂動。</li>
 * </ul>
 */
public class DanceScriptBuilder {

    /** 角度變化小於這個值（度）就不送該馬達。 */
    private static final float DEAD_BAND_DEG = 1.5f;

    /** 速度下限（度/秒），避免極小變化算出接近 0 的速度讓馬達「卡住」。 */
    private static final float MIN_SPEED_DEG_PER_SEC = 10f;

    /**
     * 速度上限（度/秒）。
     *
     * <p>TODO 實機確認：Kebbi 馬達的安全最高速度官方沒寫，150 是保守猜測。
     * 實機跑一支快歌，若馬達異音或跟不上就調低。
     * {@link DanceScriptPlayer} 播放時也用這個值再夾一次。
     */
    public static final float MAX_SPEED_DEG_PER_SEC = 150f;

    private final RobotMapper mapper = new RobotMapper();

    /**
     * 產生腳本。
     *
     * <p>Step1：逐幀 PoseAnalyzer 算特徵，不可信的幀略過。
     * Step2：RobotMapper 映射成 6 顆馬達的目標角度。
     * Step3：每顆馬達和「上一次送出的角度」比較，超過死區才送，並依角度差算速度。
     * Step4：這一步有任何馬達要動才記錄。
     *
     * @param name            舞蹈名稱
     * @param frames          已平滑、時間升冪的姿態幀
     * @param frameIntervalMs 取樣間隔（毫秒）
     * @return 播放腳本
     */
    public DanceScript build(String name, List<PoseFrame> frames, long frameIntervalMs) {
        DanceScript script = new DanceScript(name, frameIntervalMs);
        script.sourceFrameCount = frames.size();

        // 每顆馬達上一次「實際送出」的角度；死區要跟這個比，不是跟上一幀比，否則慢慢漂移會永遠不送
        Map<Integer, Float> lastSentDeg = new HashMap<>();

        for (PoseFrame frame : frames) {
            // Step1：特徵 + 可信度
            PoseFeature feature = PoseAnalyzer.analyzeFrame(frame);
            if (!feature.confident) {
                script.skippedFrameCount++;
                continue;
            }

            // Step2 + Step3：映射後只保留有變化的馬達
            List<RobotCommand> changed = new ArrayList<>();
            for (RobotCommand cmd : mapper.map(feature)) {
                Float previous = lastSentDeg.get(cmd.motorId);
                if (previous != null) {
                    float delta = Math.abs(cmd.degree - previous);
                    if (delta < DEAD_BAND_DEG) {
                        continue;
                    }
                    cmd.speedDegPerSec = clamp(delta * 1000f / frameIntervalMs,
                            MIN_SPEED_DEG_PER_SEC, MAX_SPEED_DEG_PER_SEC);
                }
                // previous == null：這顆馬達第一次出現，機器人剛從歸位姿勢出發，
                // 保留 RobotMapper 的預設速度，讓第一個動作溫和一點
                changed.add(cmd);
                lastSentDeg.put(cmd.motorId, cmd.degree);
            }

            // Step4：有東西要動才記一步
            if (!changed.isEmpty()) {
                script.steps.add(new DanceScript.Step(
                        frame.timestampMs, feature.inferredEventType, changed));
            }
        }

        script.durationMs = frames.isEmpty()
                ? 0L
                : frames.get(frames.size() - 1).timestampMs + frameIntervalMs;
        return script;
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }
}
