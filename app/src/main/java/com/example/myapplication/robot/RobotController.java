package com.example.myapplication.robot;

import android.content.Context;

import com.example.myapplication.model.RobotCommand;
import com.nuwarobotics.lib.action.ApiManager;
import com.nuwarobotics.lib.action.manager.NuwaRobotManager;
import com.nuwarobotics.lib.action.manager.NuwaVoiceManager;
import com.nuwarobotics.service.agent.NuwaRobotAPI;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Kebbi 機器人控制封裝。
 *
 * <p>用途：把「Nuwa SDK 初始化、motion 播放/停止、TTS、歸位、
 * 頭部/手部極限測試、跳舞時馬達範圍追蹤」全部集中在這裡，
 * 讓 {@link com.example.myapplication.MainActivity} 只負責 UI 與流程編排。
 *
 * <p>誰會呼叫它：
 * {@link com.example.myapplication.MainActivity} 在初始化、歸位、測試極限、播放舞蹈、停止動作時呼叫。
 *
 * <p>不負責什麼：
 * 不處理影片分析（由 {@link com.example.myapplication.analysis.PoseAnalyzer} / MediaPipe 負責），
 * 不做資料序列化（由 {@link com.example.myapplication.io.PoseIO} 負責），
 * 不知道任何人體姿勢資料（只有機器人動作指令）。
 */
public class RobotController {

    /**
     * 狀態更新回呼，供 UI 顯示進度。
     */
    public interface StatusListener {
        void onStatus(String message);
    }

    /** 歸位動作名稱關鍵字。 */
    private static final String HOME_MOTION_KEYWORD = "home";
    // 直接馬達控制測試參數
    private static final float HEAD_STEP_DEG = 5f;
    private static final float HEAD_YAW_MAX_DEG = 90f;
    private static final float HEAD_PITCH_MAX_DEG = 30f;
    /**
     * 直接馬達控制的預設速度（度/秒）。
     *
     * <p><b>單位是速度，不是時間。</b>官方 API 是
     * {@code ctlMotor(int motor, float degree, float speed)}，
     * SDK 內部呼叫 {@code setSpeedInDegreePerSec}。
     * 舊版這裡叫 {@code MOTOR_DURATION_S = 1.5f} 並當成「花 1.5 秒移動」傳入，
     * 實際意思是「每秒 1.5 度」—— 轉 90 度要 60 秒，
     * 但迴圈只等 2 秒就讀角度，於是每一步都被誤判成「卡住」。
     * 之前量到的頭部極限值（yaw 90 / pitch 30）因此並不可信，需要重測。
     */
    private static final float MOTOR_SPEED_DEG_PER_SEC = 45f;
    /** 馬達抵達後的額外安定時間（毫秒），涵蓋 AIDL 往返與機構回彈。 */
    private static final long MOTOR_SETTLE_MS = 400L;
    /** 單一姿勢最長等待時間（毫秒），避免估算失準時卡住播放迴圈。 */
    private static final long MAX_POSE_WAIT_MS = 1500L;
    private static final float MOTOR_STALL_THRESHOLD_DEG = 0.5f;
    // 跳舞播放時追蹤範圍的馬達
    private static final int[] TRACKED_MOTORS = {
            RobotMotor.NECK_Y,
            RobotMotor.NECK_Z,
            RobotMotor.RIGHT_SHOULDER_Z,
            RobotMotor.RIGHT_SHOULDER_Y,
            RobotMotor.RIGHT_SHOULDER_X,
            RobotMotor.RIGHT_ELBOW_Y,
            RobotMotor.LEFT_SHOULDER_Z,
            RobotMotor.LEFT_SHOULDER_Y,
            RobotMotor.LEFT_SHOULDER_X,
            RobotMotor.LEFT_ELBOW_Y,
    };

    private final ApiManager apiManager;
    private final NuwaRobotManager robotManager;
    private final NuwaVoiceManager voiceManager;
    private final ExecutorService executorService;
    /** 跳舞播放時追蹤的馬達範圍。 */
    private List<MotorExtremes> extremes;
    /**
     * 每顆馬達最後一次下令的角度。
     *
     * <p>用來估算「這次要轉多少度、需要等多久」。
     * 不用 {@code getMotorPresentPositionInDegree} 是因為那是同步 AIDL 呼叫，
     * 一個姿勢六顆馬達就要六次 IPC 往返，會拖慢逐幀模仿。
     */
    // 腳本播放執行緒和 UI 觸發的測試可能同時寫入，用 ConcurrentHashMap
    private final java.util.Map<Integer, Float> lastCommandedDegree =
            new java.util.concurrent.ConcurrentHashMap<>();
    /** 動作播放間隔（毫秒），由 UI 調整。 */
    private int motionStepMs = 1200;

    /**
     * 初始化 Nuwa SDK。
     *
     * <p>順序必須固定：先 ApiManager.init，再 NuwaRobotManager / NuwaVoiceManager。
     *
     * @param context Activity 或 Application Context
     */
    public RobotController(Context context) {
        apiManager = ApiManager.getInstance().init(context, context.getPackageName());
        robotManager = NuwaRobotManager.getInstance().init(context);
        voiceManager = NuwaVoiceManager.getInstance().init(context);
        executorService = Executors.newSingleThreadExecutor();
    }

    /**
     * 檢查 Nuwa 機器人 SDK 是否已初始化完成。
     *
     * @return 已初始化回傳 true
     */
    public boolean isReady() {
        return robotManager != null && robotManager.isInit();
    }

    /**
     * 釋放 Nuwa SDK 資源並關閉內部執行緒池。
     */
    public void release() {
        if (apiManager != null && apiManager.isInit()) {
            apiManager.release();
        }
        executorService.shutdownNow();
    }

    /**
     * 取得機器人內建 motion 清單。
     *
     * @return motion 名稱列表；SDK 未就緒時可能為 null
     */
    public List<String> getMotionList() {
        return robotManager != null ? robotManager.getMotionList() : null;
    }

    /**
     * 設定動作播放間隔（毫秒）。
     *
     * @param motionStepMs UI 上的間隔值
     */
    public void setMotionStepMs(int motionStepMs) {
        this.motionStepMs = motionStepMs;
    }

    /**
     * 播放指定 motion。
     *
     * @param name motion 名稱
     * @param loop 是否循環播放
     */
    public void motionPlay(String name, boolean loop) {
        robotManager.motionPlay(name, loop);
    }

    /**
     * 停止當前 motion 與 TTS。
     */
    public void stopAll() {
        if (robotManager != null && robotManager.isInit()) {
            robotManager.motionStop(true);
        }
        if (voiceManager != null && voiceManager.isInit()) {
            voiceManager.stopTTS();
        }
    }

    /**
     * 讓機器人回到歸位（中性姿勢）。
     *
     * <p>優先尋找名稱包含 "home" 的 motion 播放；若找不到，
     * 則用直接馬達控制把 {@link #TRACKED_MOTORS} 全部移回 0°，確保一定能歸位
     * （可作為關機前的結尾動作）。一律先停止當前動作。
     *
     * @param onStatus 狀態回呼
     */
    public void home(StatusListener onStatus) {
        if (!isReady()) {
            onStatus.onStatus("Nuwa robot SDK is not ready");
            return;
        }

        // Step1：停止當前動作
        robotManager.motionStop(true);

        // Step2：尋找歸位 motion
        List<String> motions = getMotionList();
        String homeMotion = null;
        if (motions != null) {
            for (String motion : motions) {
                if (motion.toLowerCase().contains(HOME_MOTION_KEYWORD)) {
                    homeMotion = motion;
                    break;
                }
            }
        }

        // Step3：有 home motion 就播放它
        if (homeMotion != null) {
            onStatus.onStatus("Returning to home: " + homeMotion);
            try {
                robotManager.motionPlay(homeMotion, false);
                Thread.sleep(motionStepMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            onStatus.onStatus("Home done");
            return;
        }

        // Step4：沒有 home motion，改用直接馬達控制全部歸 0°
        NuwaRobotAPI api = NuwaRobotAPI.getInst();
        if (api == null) {
            onStatus.onStatus("No home motion found, stopped");
            return;
        }
        onStatus.onStatus("Returning all motors to 0 deg");
        executorService.execute(() -> {
            for (int motorId : TRACKED_MOTORS) {
                if (Thread.currentThread().isInterrupted()) {
                    break;
                }
                try {
                    // 全部先送出（ctlMotor 是非同步），再一起等，馬達才會同時歸位
                    api.ctlMotor(motorId, 0f, MOTOR_SPEED_DEG_PER_SEC);
                    lastCommandedDegree.put(motorId, 0f);
                } catch (Exception e) {
                    onStatus.onStatus("Motor " + motorId + " failed: " + e.getMessage());
                }
            }
            try {
                // 最壞情況是從最大角度歸零，用 90 度估上限
                Thread.sleep(travelMs(90f, MOTOR_SPEED_DEG_PER_SEC) + MOTOR_SETTLE_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            onStatus.onStatus("Home done (all motors to 0 deg)");
        });
    }

    /**
     * 測試機器人頭部轉動極限（直接馬達控制）。
     *
     * <p>用 NuwaRobotAPI 直接以 ctlMotor 逐度移動頭部 yaw（MOTOR_NECK_Y）
     * 從 0° 每次 +5° 到 90°，再測 pitch（MOTOR_NECK_Z）到 30°。
     * 每一步讀回實際角度（getMotorPresentPositionInDegree）記錄誤差與卡住情形，
     * 最後回到 0°。若取不到 NuwaRobotAPI 實例則退回內建 head motion 測試。
     *
     * @param onStatus 狀態回呼
     */
    public void testHeadTurningLimits(StatusListener onStatus) {
        if (!isReady()) {
            onStatus.onStatus("Nuwa robot SDK is not ready");
            return;
        }

        NuwaRobotAPI api = NuwaRobotAPI.getInst();
        if (api == null) {
            testHeadMotionsFallback(onStatus);
            return;
        }

        onStatus.onStatus("Testing head limits (direct motor)");
        executorService.execute(() -> {
            StringBuilder results = new StringBuilder();
            results.append("=== Head Limits (direct motor) ===\n");

            // Step1：測試頭部 yaw（左右轉）：0 -> +90，每次 +5
            results.append("-- NECK_Y (yaw) --\n");
            boolean stalled = sweepMotor(api, NuwaRobotAPI.MOTOR_NECK_Y,
                    HEAD_YAW_MAX_DEG, results, onStatus);
            if (stalled) {
                results.append(">> 偵測到卡住（連續 3 步實際角度幾乎沒動）\n");
            }

            // Step2：測試頭部 pitch（點頭）：0 -> +30，每次 +5
            results.append("-- NECK_Z (pitch) --\n");
            sweepMotor(api, NuwaRobotAPI.MOTOR_NECK_Z, HEAD_PITCH_MAX_DEG, results, onStatus);

            // Step3：回到原點
            api.ctlMotor(NuwaRobotAPI.MOTOR_NECK_Y, 0f, MOTOR_SPEED_DEG_PER_SEC);
            api.ctlMotor(NuwaRobotAPI.MOTOR_NECK_Z, 0f, MOTOR_SPEED_DEG_PER_SEC);
            lastCommandedDegree.put(NuwaRobotAPI.MOTOR_NECK_Y, 0f);
            lastCommandedDegree.put(NuwaRobotAPI.MOTOR_NECK_Z, 0f);
            results.append("\nReturned to 0 deg");
            String finalResults = results.toString();
            onStatus.onStatus(finalResults);
        });
    }

    /**
     * 以 ctlMotor 從 0° 逐步加到 maxDeg，讀回實際角度記錄誤差與卡住。
     *
     * @param api     NuwaRobotAPI 實例
     * @param motorId 馬達 id（如 MOTOR_NECK_Y）
     * @param maxDeg  最大角度（度）
     * @param results 結果字串累積器
     * @param onStatus 狀態回呼
     * @return 是否發生卡住（連續 3 步實際角度幾乎沒動）
     */
    private boolean sweepMotor(NuwaRobotAPI api, int motorId, float maxDeg,
            StringBuilder results, StatusListener onStatus) {
        boolean stalled = false;
        float prevActual = Float.NaN;
        int noMoveStreak = 0;
        for (float target = 0f; target <= maxDeg + 0.01f; target += HEAD_STEP_DEG) {
            float step = Math.min(target, maxDeg);
            int targetInt = Math.round(step);
            onStatus.onStatus("Head test motor " + motorId + " target " + targetInt + "deg");

            try {
                api.ctlMotor(motorId, step, MOTOR_SPEED_DEG_PER_SEC);
                // 等待時間依「這一步要轉幾度」計算，不再用寫死的 2 秒。
                // 寫死 2 秒配上錯誤的速度單位，正是舊版把「還沒走完」誤判成「卡住」的原因。
                Thread.sleep(travelMs(HEAD_STEP_DEG, MOTOR_SPEED_DEG_PER_SEC) + MOTOR_SETTLE_MS);
                float actual = api.getMotorPresentPositionInDegree(motorId);
                float err = Math.abs(actual - step);
                if (!Float.isNaN(prevActual)
                        && Math.abs(actual - prevActual) < MOTOR_STALL_THRESHOLD_DEG) {
                    noMoveStreak++;
                    if (noMoveStreak >= 3) {
                        stalled = true;
                    }
                } else {
                    noMoveStreak = 0;
                }
                prevActual = actual;
                results.append(String.format(Locale.US,
                        "target=%4.0f actual=%6.1f err=%5.1f%s\n",
                        step, actual, err, stalled ? "  <-- 卡住" : ""));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                results.append(String.format(Locale.US,
                        "target=%4.0f read failed: %s\n", step, e.getMessage()));
            }
        }
        return stalled;
    }

    /**
     * 退回舊方法：找出名稱包含 "head" 的 motion 依序播放並記錄結果。
     *
     * @param onStatus 狀態回呼
     */
    private void testHeadMotionsFallback(StatusListener onStatus) {
        List<String> motions = getMotionList();
        if (motions == null || motions.isEmpty()) {
            onStatus.onStatus("No motion found on this Kebbi");
            return;
        }

        List<String> headMotions = findMotionsByKeyword(motions, "head");
        if (headMotions.isEmpty()) {
            onStatus.onStatus("No head-turning motions found");
            return;
        }

        onStatus.onStatus("Testing head limits: " + headMotions.size() + " motions");
        executorService.execute(() -> {
            StringBuilder results = new StringBuilder();
            results.append("=== Head Turning Limits (motions) ===\n");

            for (int i = 0; i < headMotions.size(); i++) {
                String motionName = headMotions.get(i);
                int stepNumber = i + 1;
                onStatus.onStatus("Head test " + stepNumber + " / " + headMotions.size()
                        + "\nPlaying: " + motionName);

                long startTime = System.currentTimeMillis();
                try {
                    robotManager.motionPlay(motionName, false);
                    Thread.sleep(motionStepMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
                long duration = System.currentTimeMillis() - startTime;

                results.append(String.format(Locale.US,
                        "%d. %s — %dms\n", stepNumber, motionName, duration));
            }

            results.append("\nTotal: ").append(headMotions.size()).append(" motions tested");
            String finalResults = results.toString();
            onStatus.onStatus(finalResults);
        });
    }

    /**
     * 測試機器人手部擺動極限。
     *
     * <p>找出所有名稱包含 "hand" 或 "wave" 的 motion，依序播放並記錄結果，
     * 讓使用者觀察手部擺動的最大範圍與時間。
     *
     * @param onStatus 狀態回呼
     */
    public void testHandWavingLimits(StatusListener onStatus) {
        if (!isReady()) {
            onStatus.onStatus("Nuwa robot SDK is not ready");
            return;
        }

        List<String> motions = getMotionList();
        if (motions == null || motions.isEmpty()) {
            onStatus.onStatus("No motion found on this Kebbi");
            return;
        }

        List<String> handMotions = findMotionsByKeyword(motions, "hand");
        List<String> waveMotions = findMotionsByKeyword(motions, "wave");
        List<String> allHandMotions = new ArrayList<>(handMotions);
        for (String m : waveMotions) {
            if (!allHandMotions.contains(m)) {
                allHandMotions.add(m);
            }
        }

        if (allHandMotions.isEmpty()) {
            onStatus.onStatus("No hand/waving motions found");
            return;
        }

        onStatus.onStatus("Testing hand limits: " + allHandMotions.size() + " motions");
        executorService.execute(() -> {
            StringBuilder results = new StringBuilder();
            results.append("=== Hand Waving Limits ===\n");

            for (int i = 0; i < allHandMotions.size(); i++) {
                String motionName = allHandMotions.get(i);
                int stepNumber = i + 1;
                onStatus.onStatus("Hand test " + stepNumber + " / " + allHandMotions.size()
                        + "\nPlaying: " + motionName);

                long startTime = System.currentTimeMillis();
                try {
                    robotManager.motionPlay(motionName, false);
                    Thread.sleep(motionStepMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
                long duration = System.currentTimeMillis() - startTime;

                results.append(String.format(Locale.US,
                        "%d. %s — %dms\n", stepNumber, motionName, duration));
            }

            results.append("\nTotal: ").append(allHandMotions.size()).append(" motions tested");
            String finalResults = results.toString();
            onStatus.onStatus(finalResults);
        });
    }

    /**
     * 在 motion 清單中尋找名稱包含關鍵字的 motion。
     *
     * @param motions 機器人內建 motion 清單
     * @param keyword 關鍵字（不區分大小寫）
     * @return 匹配的 motion 名稱列表
     */
    private List<String> findMotionsByKeyword(List<String> motions, String keyword) {
        List<String> result = new ArrayList<>();
        String lowerKeyword = keyword.toLowerCase(Locale.US);
        for (String motion : motions) {
            if (motion.toLowerCase(Locale.US).contains(lowerKeyword)) {
                result.add(motion);
            }
        }
        return result;
    }

    /**
     * 開始追蹤各馬達的角度範圍（在跳舞播放前呼叫）。
     */
    public void beginExtremeTracking() {
        extremes = new ArrayList<>();
        for (int motorId : TRACKED_MOTORS) {
            extremes.add(new MotorExtremes(motorId));
        }
    }

    /**
     * 輪詢所有追蹤馬達的目前角度，記錄各自的最大/最小值。
     *
     * <p>僅在背景執行緒呼叫（播放迴圈內）。
     */
    public void pollExtremes() {
        NuwaRobotAPI api = NuwaRobotAPI.getInst();
        if (api == null || extremes == null) {
            return;
        }
        for (MotorExtremes ex : extremes) {
            try {
                ex.update(api.getMotorPresentPositionInDegree(ex.motorId));
            } catch (Exception ignored) {
            }
        }
    }

    /**
     * 擺出一個姿勢（RobotMapper 產出的一組馬達指令）。
     *
     * <p><b>一組指令＝一個姿勢，所以六顆馬達必須同時動。</b>
     * 官方文件明確指出「all functions of Nuwa Robot SDK are async(based on AIDL) design」，
     * {@code ctlMotor} 送出後立刻返回，因此正確做法是
     * <b>先把整組送完，再統一等一次</b>。
     *
     * <p>舊版是「送一顆 → sleep 1.5 秒 → 送下一顆」，
     * 六顆馬達要 9 秒才擺完一個姿勢，而且是先轉脖子、停、再抬左肩、停……
     * 看起來像機械檢測而不是跳舞。
     *
     * <p>等待時間依「這組裡轉最多的那顆馬達」估算：{@code 角度差 / 速度}。
     * 小幅度的動作只等幾十毫秒，逐幀模仿才跟得上。
     *
     * @param commands RobotMapper 產出的指令列表
     * @param onStatus 狀態回呼
     */
    public void executeRobotCommands(List<RobotCommand> commands, StatusListener onStatus) {
        if (!isReady()) {
            onStatus.onStatus("Nuwa robot SDK is not ready");
            return;
        }
        NuwaRobotAPI api = NuwaRobotAPI.getInst();
        if (api == null) {
            onStatus.onStatus("No NuwaRobotAPI instance");
            return;
        }
        if (commands == null || commands.isEmpty()) {
            return;
        }

        // Step1：算出這組裡最慢的那顆馬達需要多久
        long waitMs = 0L;
        StringBuilder summary = new StringBuilder();
        for (RobotCommand cmd : commands) {
            float from = lastCommandedDegree.containsKey(cmd.motorId)
                    ? lastCommandedDegree.get(cmd.motorId)
                    : 0f;
            waitMs = Math.max(waitMs, travelMs(Math.abs(cmd.degree - from), cmd.speedDegPerSec));
            summary.append('M').append(cmd.motorId).append('=')
                    .append(Math.round(cmd.degree)).append("deg ");
        }

        // Step2：整組一次送出，馬達同時開始動
        for (RobotCommand cmd : commands) {
            try {
                api.ctlMotor(cmd.motorId, cmd.degree, cmd.speedDegPerSec);
                lastCommandedDegree.put(cmd.motorId, cmd.degree);
            } catch (Exception e) {
                onStatus.onStatus("Motor " + cmd.motorId + " failed: " + e.getMessage());
            }
        }
        onStatus.onStatus("Pose: " + summary.toString().trim());

        // Step3：整組只等一次
        try {
            Thread.sleep(Math.min(waitMs + MOTOR_SETTLE_MS, MAX_POSE_WAIT_MS));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 送出單一馬達指令，立刻返回、不等待。
     *
     * <p>給 {@link DanceScriptPlayer} 用：腳本的時間軸由播放器自己掌控，
     * 這裡只負責把指令交給 SDK（ctlMotor 本身就是非同步的）。
     *
     * @param cmd 馬達指令
     * @return 成功交給 SDK 回傳 true；SDK 未就緒或呼叫失敗回傳 false
     */
    public boolean sendMotorCommand(RobotCommand cmd) {
        if (!isReady()) {
            return false;
        }
        NuwaRobotAPI api = NuwaRobotAPI.getInst();
        if (api == null) {
            return false;
        }
        try {
            api.ctlMotor(cmd.motorId, cmd.degree, cmd.speedDegPerSec);
            lastCommandedDegree.put(cmd.motorId, cmd.degree);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 估算馬達轉動需要的時間。
     *
     * @param deltaDeg       要轉的角度差（度，取絕對值）
     * @param speedDegPerSec 速度（度/秒）
     * @return 需要的毫秒數；速度非正時回傳 0
     */
    private static long travelMs(float deltaDeg, float speedDegPerSec) {
        if (speedDegPerSec <= 0f || deltaDeg <= 0f) {
            return 0L;
        }
        return (long) (deltaDeg / speedDegPerSec * 1000f);
    }

    /**
     * 格式化跳舞播放期間各馬達的實際角度範圍摘要。
     *
     * @return 每個馬達的 min ~ max 字串；沒有資料時回傳空字串
     */
    public String getExtremesSummary() {
        if (extremes == null || extremes.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder("--- Motor ranges reached ---\n");
        for (MotorExtremes ex : extremes) {
            if (ex.hasData()) {
                sb.append(String.format(Locale.US, "M%d: %.0f ~ %.0f deg\n",
                        ex.motorId, ex.min, ex.max));
            }
        }
        return sb.toString();
    }

    /**
     * 馬達範圍追蹤的內部資料類。
     *
     * <p>記錄單一馬達在播放期間的最小/最大角度。
     */
    private static class MotorExtremes {
        /** 馬達 id（對應 NuwaRobotAPI.MOTOR_*）。 */
        final int motorId;
        /** 觀察到的最小角度。 */
        float min = Float.POSITIVE_INFINITY;
        /** 觀察到的最大角度。 */
        float max = Float.NEGATIVE_INFINITY;

        MotorExtremes(int motorId) {
            this.motorId = motorId;
        }

        void update(float position) {
            if (position < min) {
                min = position;
            }
            if (position > max) {
                max = position;
            }
        }

        boolean hasData() {
            return min <= max;
        }
    }
}
