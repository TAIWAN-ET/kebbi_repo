package com.example.myapplication.robot;

import com.example.myapplication.model.DanceScript;
import com.example.myapplication.model.RobotCommand;

import java.util.Collections;
import java.util.List;
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
 *   <li><b>換臉</b>：腳本的 {@link DanceScript#faces} 和馬達步驟用同一個時間軸送出（同一時間先換臉）；
 *       沒給 {@link FaceSink} 時忽略。開頭之前的換臉事件只送最後一個。</li>
 *   <li><b>結束</b>：正常播完可選擇歸位；按停止則停在原地（歸位交給 Home 按鈕）。
 *       有換過臉的話，不論怎麼結束都把臉還原（臉部動畫會蓋住 App 畫面）。</li>
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

    /** 把換臉指令交給機器人的臉（App 裡是 RobotController，PC 測試時印出來）。 */
    public interface FaceSink {
        /**
         * @param face Kebbi 臉部動畫名稱；空字串＝回到預設臉
         * @return 成功送出回傳 true
         */
        boolean show(String face);

        /** 播放結束：停掉臉部動畫、把臉收起來。 */
        void reset();
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
            RobotMotor.LEFT_SHOULDER_X, RobotMotor.RIGHT_SHOULDER_X,
            RobotMotor.LEFT_SHOULDER_Z, RobotMotor.RIGHT_SHOULDER_Z,
            RobotMotor.LEFT_ELBOW_Y, RobotMotor.RIGHT_ELBOW_Y,
    };

    private final MotorSink sink;
    private final FaceSink faceSink;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private volatile Thread worker;
    private volatile boolean playing;
    private volatile boolean stopRequested;

    /**
     * @param sink 實際送出馬達指令的地方
     */
    public DanceScriptPlayer(MotorSink sink) {
        this(sink, null);
    }

    /**
     * @param sink     實際送出馬達指令的地方
     * @param faceSink 實際換臉的地方；null 代表不播表情
     */
    public DanceScriptPlayer(MotorSink sink, FaceSink faceSink) {
        this.sink = sink;
        this.faceSink = faceSink;
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
        List<DanceScript.FaceEvent> faces = faceSink == null
                ? Collections.<DanceScript.FaceEvent>emptyList() : script.faces;
        RunStats stats = new RunStats();
        boolean completed = false;
        String error = null;

        worker = Thread.currentThread();
        try {
            if (stopRequested) {
                return;
            }
            // Step1：預備 —— 先擺出第一個姿勢、換上開頭的臉，等機器人就位
            DanceScript.Step first = script.steps.get(0);
            long originMs = first.timeMs;
            int faceIndex = 0;
            DanceScript.FaceEvent openingFace = null;
            while (faceIndex < faces.size() && faces.get(faceIndex).timeMs <= originMs) {
                openingFace = faces.get(faceIndex++);
            }
            if (openingFace != null) {
                sendFace(openingFace, stats);
            }
            stats.rejected += sendStep(first);
            stats.sentSteps++;
            listener.onStep(0, total, first);
            Thread.sleep(PREROLL_MS);

            // Step2：以第一步為零點，照絕對時間送出之後每一步（以及在它之前到期的換臉）
            long startNanos = System.nanoTime();
            for (int i = 1; i < total; i++) {
                DanceScript.Step step = script.steps.get(i);
                while (faceIndex < faces.size() && faces.get(faceIndex).timeMs <= step.timeMs) {
                    DanceScript.FaceEvent face = faces.get(faceIndex++);
                    waitUntil(face.timeMs - originMs, startNanos, stats);
                    if (stopRequested) {
                        break;
                    }
                    sendFace(face, stats);
                }
                waitUntil(step.timeMs - originMs, startNanos, stats);
                if (stopRequested) {
                    break;
                }
                stats.rejected += sendStep(step);
                stats.sentSteps++;
                listener.onStep(i, total, step);
            }

            // Step3：送完最後一步之後的換臉，並等最後一步的動作做完（腳本長度 - 最後一步時間）
            while (!stopRequested && faceIndex < faces.size()
                    && faces.get(faceIndex).timeMs < script.durationMs) {
                DanceScript.FaceEvent face = faces.get(faceIndex++);
                waitUntil(face.timeMs - originMs, startNanos, stats);
                if (!stopRequested) {
                    sendFace(face, stats);
                }
            }
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
            if (stats.sentFaces + stats.rejectedFaces > 0) {
                try {
                    faceSink.reset();
                } catch (RuntimeException e) {
                    if (error == null) {
                        error = e.toString();
                    }
                }
            }
            String message = error != null
                    ? "Script playback error: " + error
                    : String.format(Locale.US,
                            "%s \"%s\": %d/%d steps, rejected cmds=%d, faces=%d (rejected %d), max late=%dms",
                            completed ? "Finished" : "Stopped", script.name,
                            stats.sentSteps, total, stats.rejected,
                            stats.sentFaces, stats.rejectedFaces, stats.maxLateMs);
            playing = false;
            listener.onFinished(completed, message);
        }
    }

    /** 睡到「開始時間 + dueMs」；已經過了就記錄落後多少，立刻返回。 */
    private static void waitUntil(long dueMs, long startNanos, RunStats stats) throws InterruptedException {
        long waitMs = dueMs - elapsedMs(startNanos);
        if (waitMs > 0) {
            Thread.sleep(waitMs);
        } else {
            stats.maxLateMs = Math.max(stats.maxLateMs, -waitMs);
        }
    }

    private void sendFace(DanceScript.FaceEvent face, RunStats stats) {
        if (faceSink.show(face.face)) {
            stats.sentFaces++;
        } else {
            stats.rejectedFaces++;
        }
    }

    /** 一次播放的統計（只在播放執行緒上讀寫）。 */
    private static final class RunStats {
        int sentSteps;
        int rejected;
        int sentFaces;
        int rejectedFaces;
        long maxLateMs;
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
        float minDeg = RobotMapper.minDegFor(cmd.motorId);
        float maxDeg = RobotMapper.maxDegFor(cmd.motorId);
        if (Float.isNaN(maxDeg) || Float.isNaN(cmd.degree) || Float.isNaN(cmd.speedDegPerSec)) {
            return null;
        }
        float degree = Math.max(minDeg, Math.min(maxDeg, cmd.degree));
        float speed = Math.max(MIN_SPEED_DEG_PER_SEC,
                Math.min(RobotMapper.maxSpeedFor(cmd.motorId), cmd.speedDegPerSec));
        return new RobotCommand(cmd.motorId, degree, speed);
    }

    private static long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000L;
    }
}
