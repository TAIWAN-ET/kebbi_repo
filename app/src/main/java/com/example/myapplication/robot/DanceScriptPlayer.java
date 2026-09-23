package com.example.myapplication.robot;

import com.example.myapplication.model.DanceScript;
import com.example.myapplication.model.RobotCommand;

import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 照時間播放 {@link DanceScript}：在每一步的時間點把指令送給馬達。
 *
 * <p>用途：PC Offload 的機器人端。電腦產生腳本，Kebbi 只負責準時送出指令。
 *
 * <p>誰會呼叫它：{@code MainActivity}（按鈕 / 遠端指令觸發播放與停止）。
 *
 * <p>不負責什麼：不做分析、不讀檔、不做網路。馬達怎麼動由 {@link MotorSink} 決定，
 * 在 App 裡是 {@link RobotController#sendMotorCommand}，在 PC 測試時可以換成假的。
 * 所以本類別是純 Java，不依賴 Android / Nuwa SDK。
 *
 * <p>播放流程：
 * <ol>
 *   <li><b>預備</b>：先送第一步，等 {@link #PREROLL_MS} 讓機器人從任何姿勢轉到開頭姿勢，
 *       正式計時才開始，第一秒的動作不會因為「還在趕路」而全部落後。</li>
 *   <li><b>對齊絕對時間</b>：每一步都用「開始時間 + 該步的 t」計算要等多久，
 *       而不是一步接一步 sleep，送指令的耗時不會一路累積成延遲。
 *       落後時立刻補送，不跳步（每一步只列有變化的馬達，跳過就會漏掉目標角度）。</li>
 *   <li><b>安全夾位</b>：送出前再夾一次角度與速度，不是 RobotMapper 驅動的馬達一律拒送。
 *       腳本可能被手動改過，或來自比較舊的產生器，機器人端不能完全信任它。</li>
 *   <li><b>結束</b>：正常播完可選擇歸位；按停止則停在原地（歸位交給 Home 按鈕）。</li>
 * </ol>
 */
public class DanceScriptPlayer {

    /** 把一個馬達指令交給硬體。 */
    public interface MotorSink {
        /**
         * @param cmd 已夾位的指令
         * @return 成功送出回傳 true
         */
        boolean send(RobotCommand cmd);
    }

    /** 播放進度回呼（在播放執行緒上呼叫，UI 端要自己切回主執行緒）。 */
    public interface Listener {
        /** 第 index 步（從 0 起算）剛送出。 */
        void onStep(int index, int total, DanceScript.Step step);

        /**
         * 播放結束。
         *
         * @param completed 正常播完為 true，被停止或出錯為 false
         * @param message   結束摘要
         */
        void onFinished(boolean completed, String message);
    }

    /** 送出第一步之後、開始計時之前的等待時間（毫秒）。 */
    private static final long PREROLL_MS = 1500L;

    /** 播完歸位時的速度（度/秒），和 RobotController 的歸位速度同級。 */
    private static final float HOME_SPEED_DEG_PER_SEC = 45f;

    /** 速度下限，避免 0 或負數速度送進 SDK。 */
    private static final float MIN_SPEED_DEG_PER_SEC = 1f;

    /** RobotMapper 會驅動、播完要歸位的馬達。 */
    private static final int[] MAPPED_MOTORS = {
            RobotMotor.NECK_YAW, RobotMotor.NECK_PITCH,
            RobotMotor.LEFT_SHOULDER_Y, RobotMotor.RIGHT_SHOULDER_Y,
            RobotMotor.LEFT_ELBOW_Y, RobotMotor.RIGHT_ELBOW_Y,
    };

    private final MotorSink sink;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private volatile Thread worker;
    private volatile boolean playing;
    private volatile boolean stopRequested;

    /**
     * @param sink 實際送出馬達指令的地方
     */
    public DanceScriptPlayer(MotorSink sink) {
        this.sink = sink;
    }

    /**
     * 開始播放（非同步，立刻返回）。
     *
     * @param script     要播放的腳本
     * @param returnHome 正常播完後是否把馬達歸 0 度
     * @param listener   進度回呼
     * @return 已開始播放回傳 true；正在播放中或腳本是空的回傳 false
     */
    public synchronized boolean play(DanceScript script, boolean returnHome, Listener listener) {
        if (playing || script == null || script.steps.isEmpty()) {
            return false;
        }
        playing = true;
        stopRequested = false;
        executor.execute(() -> run(script, returnHome, listener));
        return true;
    }

    /**
     * 停止播放；機器人停在最後送出的姿勢。
     *
     * <p>用旗標 + 中斷 worker 執行緒，而不是 Future.cancel：
     * 若任務還沒開始就被 cancel，run() 永遠不會執行，playing 會卡在 true。
     * 現在任務一定會跑，開頭看到 stopRequested 就直接結束。
     */
    public void stop() {
        stopRequested = true;
        Thread t = worker;
        if (t != null) {
            t.interrupt();
        }
    }

    /** @return 是否正在播放 */
    public boolean isPlaying() {
        return playing;
    }

    /** 停止並釋放執行緒（Activity onDestroy 呼叫）。 */
    public void shutdown() {
        stop();
        executor.shutdownNow();
    }

    private void run(DanceScript script, boolean returnHome, Listener listener) {
        int total = script.steps.size();
        int sentSteps = 0;
        int rejected = 0;
        long maxLateMs = 0L;
        boolean completed = false;
        String error = null;

        worker = Thread.currentThread();
        try {
            if (stopRequested) {
                return;
            }
            // Step1：預備 —— 先擺出第一個姿勢，等機器人就位
            DanceScript.Step first = script.steps.get(0);
            rejected += sendStep(first);
            sentSteps++;
            listener.onStep(0, total, first);
            Thread.sleep(PREROLL_MS);

            // Step2：以第一步為零點，照絕對時間送出之後每一步
            long startNanos = System.nanoTime();
            long originMs = first.timeMs;
            for (int i = 1; i < total; i++) {
                DanceScript.Step step = script.steps.get(i);
                long dueMs = step.timeMs - originMs;
                long waitMs = dueMs - elapsedMs(startNanos);
                if (waitMs > 0) {
                    Thread.sleep(waitMs);
                } else {
                    maxLateMs = Math.max(maxLateMs, -waitMs);
                }
                if (stopRequested) {
                    break;
                }
                rejected += sendStep(step);
                sentSteps++;
                listener.onStep(i, total, step);
            }

            // Step3：等最後一步的動作做完（腳本長度 - 最後一步時間）
            if (!stopRequested) {
                long endWaitMs = (script.durationMs - originMs) - elapsedMs(startNanos);
                if (endWaitMs > 0) {
                    Thread.sleep(endWaitMs);
                }
                completed = !stopRequested;
            }

            // Step4：正常播完才歸位
            if (completed && returnHome) {
                for (int motorId : MAPPED_MOTORS) {
                    sink.send(new RobotCommand(motorId, 0f, HOME_SPEED_DEG_PER_SEC));
                }
            }
        } catch (InterruptedException e) {
            // stop() 中斷 sleep：正常的停止路徑
        } catch (RuntimeException e) {
            // 回呼或 SDK 丟出的例外不能讓 playing 卡在 true
            error = e.toString();
        } finally {
            worker = null;
            // 清掉可能殘留的中斷旗標，executor 的下一個任務才不會一開始就被中斷
            Thread.interrupted();
            String message = error != null
                    ? "Script playback error: " + error
                    : String.format(Locale.US, "%s \"%s\": %d/%d steps, rejected cmds=%d, max late=%dms",
                            completed ? "Finished" : "Stopped", script.name,
                            sentSteps, total, rejected, maxLateMs);
            playing = false;
            listener.onFinished(completed, message);
        }
    }

    /**
     * 夾位後送出一步的所有指令。
     *
     * @return 被拒送（未知馬達或送出失敗）的指令數
     */
    private int sendStep(DanceScript.Step step) {
        int rejected = 0;
        for (RobotCommand cmd : step.commands) {
            RobotCommand safe = sanitize(cmd);
            if (safe == null || !sink.send(safe)) {
                rejected++;
            }
        }
        return rejected;
    }

    /**
     * 機器人端的最後一道防線。
     *
     * @return 夾位後的新指令；不是 RobotMapper 驅動的馬達回傳 null
     */
    static RobotCommand sanitize(RobotCommand cmd) {
        float maxDeg = RobotMapper.maxDegFor(cmd.motorId);
        if (maxDeg < 0f || Float.isNaN(cmd.degree) || Float.isNaN(cmd.speedDegPerSec)) {
            return null;
        }
        float degree = Math.max(-maxDeg, Math.min(maxDeg, cmd.degree));
        float speed = Math.max(MIN_SPEED_DEG_PER_SEC,
                Math.min(DanceScriptBuilder.MAX_SPEED_DEG_PER_SEC, cmd.speedDegPerSec));
        return new RobotCommand(cmd.motorId, degree, speed);
    }

    private static long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000L;
    }
}
