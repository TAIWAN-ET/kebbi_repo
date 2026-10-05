package com.example.myapplication;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.widget.Button;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.activity.EdgeToEdge;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.example.myapplication.analysis.VideoAnalyzer;
import com.example.myapplication.io.DanceScriptIO;
import com.example.myapplication.model.DanceScript;
import com.example.myapplication.model.KeyframeEntry;
import com.example.myapplication.model.PoseFeature;
import com.example.myapplication.model.RobotCommand;
import com.example.myapplication.net.ScriptServer;
import com.example.myapplication.robot.DanceScriptPlayer;
import com.example.myapplication.robot.MotionPoseMap;
import com.example.myapplication.robot.RobotController;
import com.example.myapplication.robot.RobotMapper;

import android.os.Handler;
import android.os.Looper;
import java.io.File;
import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Kebbi 看影片學跳舞專題的主 Activity。
 *
 * <p>用途：提供 Android UI 讓使用者進行以下操作：
 *  <ul>
 *   <li>分析影片（上半身角度計算 + 逐幀日誌）</li>
 *   <li>分析內建影片（dance_input.mp4）</li>
 *   <li>播放舞蹈動作、歸位、停止動作</li>
 *   <li>測試機器人頭部/手部動作極限</li>
 * </ul>
 *
 * <p>誰會呼叫它：
 * Android 系統在 App 啟動時建立此 Activity。
 * 使用者透過按鈕觸發各項功能。
 *
 * <p>不負責什麼：
 * 不直接處理 CSV/JSON 序列化（由 {@link com.example.myapplication.io.PoseIO} 負責），
 * 不計算角度或距離（由 {@link com.example.myapplication.math.PoseMath} 和
 * {@link com.example.myapplication.analysis.PoseAnalyzer} 負責），
 * 不做 round-trip 驗證（由 {@link com.example.myapplication.io.PosePipeline} 負責），
 * 不直接控制機器人動作（由 {@link RobotController} 負責），
 * 不處理 MediaPipe / Camera / 影片解析（由 {@link VideoAnalyzer} / camera 層負責）。
 *
 * <p>主要資料流：
 * 按按鈕 → VideoAnalyzer（影片→PoseFeature）→ RobotMapper（PoseFeature→RobotCommand）
 * → RobotController（執行）。
 */
public class MainActivity extends AppCompatActivity {
    // 動作播放間隔可調節（毫秒），供用戶在 UI 上調整
    private static final int MOTION_STEP_MIN_MS = 500;
    private static final int MOTION_STEP_MAX_MS = 5000;
    private static final int MOTION_STEP_DEFAULT_MS = 1200;
    // 計時器更新間隔（毫秒）
    private static final long TIMER_UPDATE_INTERVAL_MS = 100L;
    // 跳舞播放時輪詢馬達位置的間隔（毫秒）
    private static final long MOTOR_POLL_INTERVAL_MS = 500L;
    // 分析結果映射 RobotCommand 預覽時最多顯示幾幀
    private static final int COMMAND_PREVIEW_FRAMES = 3;
    // PC 產生的舞蹈腳本：adb push 到這個檔名，或由 ScriptServer 收到後存成這個檔名
    private static final String DANCE_SCRIPT_FILE = "dance_script.json";

    private RobotController robotController;
    private DanceScriptPlayer scriptPlayer;
    private ScriptServer scriptServer;
    private Button remoteButton;
    // 目前載入的腳本（按鈕播放或遠端 /play 都播這份）
    private volatile DanceScript loadedScript;
    private VideoAnalyzer videoAnalyzer;
    private TextView statusText;
    private TextView timerText;
    private ExecutorService executorService;
    private ActivityResultLauncher<Intent> pickVideoLauncher;
    private List<VideoAnalyzer.DanceStep> latestDanceSteps = Collections.emptyList();
    private List<KeyframeEntry> latestKeyframes = Collections.emptyList();
    private List<PoseFeature> latestFeatures = Collections.emptyList();
    private volatile boolean dancePlaybackStopped = false;
    private int motionStepMs = MOTION_STEP_DEFAULT_MS;
    // 計時器
    private Handler timerHandler;
    private long danceStartTime;
    private boolean isTimerRunning;
    // RobotController 狀態回呼：把訊息顯示到 statusText
    private final RobotController.StatusListener statusListener = message ->
            runOnUiThread(() -> statusText.setText(message));
    // VideoAnalyzer 結果回呼：顯示摘要、保存舞蹈步驟與 PoseFeature，
    // 並把特徵映射成 RobotCommand 預覽
    private final VideoAnalyzer.ResultListener analyzeListener = new VideoAnalyzer.ResultListener() {
        @Override
        public void onAnalyzed(String summary, List<PoseFeature> features,
                List<VideoAnalyzer.DanceStep> danceSteps, List<KeyframeEntry> keyframes) {
            if (danceSteps != null) {
                latestDanceSteps = danceSteps;
            }
            if (keyframes != null) {
                latestKeyframes = keyframes;
            }
            if (features != null) {
                latestFeatures = features;
            }
            runOnUiThread(() -> statusText.setText(summary + keyframePreview() + commandPreview()));
        }

        @Override
        public void onError(String message) {
            runOnUiThread(() -> statusText.setText(message));
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_main);
        statusText = findViewById(R.id.statusText);
        timerText = findViewById(R.id.timerText);

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });

        // Step1：初始化 RobotController（內部完成 Nuwa SDK 初始化）
        robotController = new RobotController(this);

        // Step2：建立單執行緒後端用於非同步分析
        executorService = Executors.newSingleThreadExecutor();

        // Step3：建立計時器 Handler
        timerHandler = new Handler(Looper.getMainLooper());

        // Step4：初始化 VideoAnalyzer（自行建立 MediaPipe Pose Landmarker，
        //        負責 影片 → PoseFrame → PoseFeature）
        videoAnalyzer = new VideoAnalyzer(this, executorService);

        // Step5：PC Offload —— 腳本播放器（馬達指令交給 RobotController）與 Wi-Fi 接收器
        scriptPlayer = new DanceScriptPlayer(robotController::sendMotorCommand);
        scriptServer = new ScriptServer(ScriptServer.DEFAULT_PORT, scriptServerHandler);

        // Step6：設定影片選擇器
        setupVideoPicker();

        // Step7：綁定 UI 按鈕並註冊點擊事件
        Button pickVideoButton = findViewById(R.id.pickVideoButton);
        Button playDanceButton = findViewById(R.id.playDanceButton);
        Button playScriptButton = findViewById(R.id.playScriptButton);
        remoteButton = findViewById(R.id.remoteButton);
        Button stopButton = findViewById(R.id.stopButton);
        Button homeButton = findViewById(R.id.homeButton);
        Button headLimitButton = findViewById(R.id.headLimitButton);
        Button handLimitButton = findViewById(R.id.handLimitButton);
        Button mapperTestButton = findViewById(R.id.mapperTestButton);
        SeekBar stepSeekBar = findViewById(R.id.stepSeekBar);
        TextView stepLabel = findViewById(R.id.stepLabel);

        pickVideoButton.setOnClickListener(v -> {
            statusText.setText("分析影片前 10 秒（上半身）...");
            videoAnalyzer.analyzeBuiltInClip(analyzeListener);
        });
        playDanceButton.setOnClickListener(v -> playKeyframeMotion());
        playScriptButton.setOnClickListener(v -> playScriptFromFile());
        remoteButton.setOnClickListener(v -> toggleRemote());
        stopButton.setOnClickListener(v -> stopMotion());
        homeButton.setOnClickListener(v -> robotController.home(statusListener));
        headLimitButton.setOnClickListener(v -> robotController.testHeadTurningLimits(statusListener));
        handLimitButton.setOnClickListener(v -> robotController.testHandWavingLimits(statusListener));
        mapperTestButton.setOnClickListener(v -> testRobotMapper());
        stepSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                motionStepMs = progress;
                robotController.setMotionStepMs(progress);
                stepLabel.setText("動作間隔: " + progress + "ms");
            }
            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }
            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        });
        stepSeekBar.setProgress(MOTION_STEP_DEFAULT_MS);
        robotController.setMotionStepMs(MOTION_STEP_DEFAULT_MS);
    }

    /**
     * Play the second-based keyframe table first.
     */
    private void playKeyframeMotion() {
        if (!robotController.isReady()) {
            statusText.setText("Nuwa robot SDK is not ready");
            return;
        }

        List<KeyframeEntry> keyframes = latestKeyframes;
        if (keyframes == null || keyframes.isEmpty()) {
            playDanceMotion();
            return;
        }

        robotController.home(statusListener);
        executorService.execute(() -> {
            try {
                Thread.sleep(motionStepMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }

            List<String> motions = robotController.getMotionList();
            if (motions == null || motions.isEmpty()) {
                runOnUiThread(() -> statusText.setText("No motion found on this Kebbi"));
                return;
            }

            Map<String, String> eventToMotion = MotionPoseMap.buildEventToMotionMap(motions);
            dancePlaybackStopped = false;
            startTimer();
            robotController.beginExtremeTracking();
            runOnUiThread(() -> statusText.setText("Playing keyframes: " + keyframes.size()));

            for (int i = 0; i < keyframes.size(); i++) {
                if (dancePlaybackStopped || Thread.currentThread().isInterrupted()) {
                    break;
                }

                KeyframeEntry keyframe = keyframes.get(i);
                String plannedMotion = keyframe.robotMotion == null ? "" : keyframe.robotMotion.trim();
                String motionName;
                if ("NEUTRAL".equals(keyframe.eventType)) {
                    motionName = null;
                } else if (plannedMotion.isEmpty() || plannedMotion.equals(keyframe.eventType)) {
                    motionName = eventToMotion.get(keyframe.eventType);
                } else {
                    motionName = plannedMotion;
                }
                if (motionName != null && motionName.isEmpty()) {
                    motionName = eventToMotion.get(keyframe.eventType);
                }
                final int secondNumber = keyframe.secondIndex;
                final String statusMotion = motionName;
                runOnUiThread(() -> statusText.setText(
                        "Second " + secondNumber
                                + "\n" + keyframe.eventType
                                + "\nAmp: " + Math.round(keyframe.amplitudePercent) + "%"
                                + (statusMotion == null ? "\nHold/Stop"
                                : "\nPlaying: " + statusMotion)));

                if (motionName != null) {
                    robotController.motionPlay(motionName, false);
                } else {
                    robotController.stopAll();
                }

                long deadline = System.currentTimeMillis() + motionStepMs;
                while (System.currentTimeMillis() < deadline && !dancePlaybackStopped) {
                    robotController.pollExtremes();
                    try {
                        Thread.sleep(MOTOR_POLL_INTERVAL_MS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }

            if (!dancePlaybackStopped) {
                robotController.stopAll();
                String extremesSummary = robotController.getExtremesSummary();
                runOnUiThread(() -> {
                    stopTimer();
                    statusText.setText("Keyframe playback finished\n" + extremesSummary);
                });
            }
        });
    }

    // ------------------------------------------------------------------
    // PC Offload：播放電腦產生的 dance_script.json
    // ------------------------------------------------------------------

    /**
     * 按鈕播放：讀 App 外部檔案目錄的 dance_script.json（adb push 或遠端接收存下來的）。
     *
     * <p>每次按都重新讀檔，電腦端重新產生、重新 push 之後不用重開 App。
     */
    private void playScriptFromFile() {
        File file = new File(getExternalFilesDir(null), DANCE_SCRIPT_FILE);
        if (!file.exists()) {
            statusText.setText("找不到 " + DANCE_SCRIPT_FILE + "\n請先:\nadb push dance_script.json "
                    + file.getAbsolutePath() + "\n或開啟遠端接收，用 pc/send_script.py 傳送");
            return;
        }
        executorService.execute(() -> {
            try {
                loadedScript = DanceScriptIO.read(file);
            } catch (IOException | RuntimeException e) {
                runOnUiThread(() -> statusText.setText("腳本讀取失敗: " + e.getMessage()));
                return;
            }
            String message = startScriptPlayback();
            runOnUiThread(() -> statusText.setText(message));
        });
    }

    /**
     * 播放 {@link #loadedScript}。任何執行緒都可以呼叫（按鈕或 ScriptServer）。
     *
     * @return 給使用者 / 電腦看的結果訊息
     */
    private String startScriptPlayback() {
        DanceScript script = loadedScript;
        if (script == null) {
            return "No dance script loaded";
        }
        if (!robotController.isReady()) {
            return "Nuwa robot SDK is not ready";
        }
        if (scriptPlayer.isPlaying()) {
            return "Already playing; stop first";
        }
        // 先停掉舊的 keyframe / feature 播放迴圈，避免兩邊同時搶馬達
        dancePlaybackStopped = true;
        robotController.stopAll();

        boolean started = scriptPlayer.play(script, true, scriptListener);
        if (!started) {
            return "Script not started (empty script?)";
        }
        runOnUiThread(this::startTimer);
        return "Playing script: " + script.describe();
    }

    /** 腳本播放進度 → 畫面。 */
    private final DanceScriptPlayer.Listener scriptListener = new DanceScriptPlayer.Listener() {
        @Override
        public void onStep(int index, int total, DanceScript.Step step) {
            runOnUiThread(() -> statusText.setText(String.format(Locale.US,
                    "Script step %d / %d  (t=%.1fs)\n%s\n%s",
                    index + 1, total, step.timeMs / 1000f,
                    step.event.isEmpty() ? "-" : step.event,
                    RobotMapper.describeCommands(step.commands))));
        }

        @Override
        public void onFinished(boolean completed, String message) {
            runOnUiThread(() -> {
                stopTimer();
                statusText.setText(message);
            });
        }
    };

    /** 開 / 關 Wi-Fi 遠端接收。只在需要時開：同一區網的任何人都能讓機器人動。 */
    private void toggleRemote() {
        if (scriptServer.isRunning()) {
            scriptServer.stop();
            remoteButton.setText(R.string.remote_on_button);
            statusText.setText("遠端接收已關閉");
            return;
        }
        try {
            scriptServer.start();
        } catch (IOException e) {
            statusText.setText("遠端接收啟動失敗: " + e.getMessage());
            return;
        }
        remoteButton.setText(R.string.remote_off_button);
        StringBuilder sb = new StringBuilder("遠端接收已開啟，電腦端執行:\n");
        List<String> ips = ScriptServer.localIpv4Addresses();
        if (ips.isEmpty()) {
            sb.append("(找不到 IP，請確認 Kebbi 已連上 Wi-Fi)");
        }
        for (String ip : ips) {
            sb.append("python send_script.py ").append(ip)
                    .append(" <dance_script.json> --play\n");
        }
        statusText.setText(sb.toString());
    }

    /** ScriptServer 的指令處理（在 ScriptServer 的背景執行緒上呼叫）。 */
    private final ScriptServer.Handler scriptServerHandler = new ScriptServer.Handler() {
        @Override
        public String onScript(DanceScript script, boolean play) throws Exception {
            // 存檔：之後按「播放腳本」按鈕也能重播同一份
            DanceScriptIO.write(new File(getExternalFilesDir(null), DANCE_SCRIPT_FILE), script);
            loadedScript = script;
            String message = "Received " + script.describe();
            if (play) {
                message += "\n" + startScriptPlayback();
            }
            final String shown = message;
            runOnUiThread(() -> statusText.setText(shown));
            return message;
        }

        @Override
        public String onPlay() {
            String message = startScriptPlayback();
            runOnUiThread(() -> statusText.setText(message));
            return message;
        }

        @Override
        public String onStop() {
            runOnUiThread(MainActivity.this::stopMotion);
            return "Stopped";
        }

        @Override
        public String onHome() {
            scriptPlayer.stop();
            robotController.home(statusListener);
            return "Homing";
        }

        @Override
        public String statusJson() {
            DanceScript script = loadedScript;
            return "{\"ok\": true"
                    + ", \"robotReady\": " + robotController.isReady()
                    + ", \"playing\": " + scriptPlayer.isPlaying()
                    + ", \"script\": " + (script == null ? "null" : ScriptServer.quote(script.describe()))
                    + "}";
        }
    };

    /**
     * 設定影片選擇器，用於從檔案系統選擇影片。
     */
    private void setupVideoPicker() {
        pickVideoLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    if (result.getResultCode() != RESULT_OK || result.getData() == null) {
                        statusText.setText("No video selected");
                        return;
                    }

                    Uri videoUri = result.getData().getData();
                    if (videoUri != null) {
                        statusText.setText("Analyzing dance video...");
                        videoAnalyzer.analyzeDanceVideo(videoUri, analyzeListener);
                    }
                });
    }

    /**
     * 播放舞蹈動作（根據 latestDanceSteps 中的事件序列）。
     *
     * <p>若無舞蹈步驟，播放第一個可用的 motion；
     * 若機器人 SDK 未準備，顯示提示。
     */
    private void playDanceMotion() {
        if (!robotController.isReady()) {
            statusText.setText("Nuwa robot SDK is not ready");
            return;
        }

        List<PoseFeature> features = latestFeatures;
        if (features != null && !features.isEmpty()) {
            robotController.home(statusListener);
            executorService.execute(() -> {
                try {
                    Thread.sleep(motionStepMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }

                dancePlaybackStopped = false;
                startTimer();
                robotController.beginExtremeTracking();
                RobotMapper mapper = new RobotMapper();
                runOnUiThread(() -> statusText.setText("Playing analyzed pose features: "
                        + features.size()));

                for (int i = 0; i < features.size(); i++) {
                    if (dancePlaybackStopped || Thread.currentThread().isInterrupted()) {
                        break;
                    }

                    PoseFeature feature = features.get(i);
                    List<RobotCommand> commands = mapper.map(feature);
                    final int frameNumber = i + 1;
                    runOnUiThread(() -> statusText.setText(
                            "Pose frame " + frameNumber + " / " + features.size()
                                    + "\n" + feature.describe()));
                    robotController.executeRobotCommands(commands, statusListener);

                    long deadline = System.currentTimeMillis() + motionStepMs;
                    while (System.currentTimeMillis() < deadline && !dancePlaybackStopped) {
                        robotController.pollExtremes();
                        try {
                            Thread.sleep(MOTOR_POLL_INTERVAL_MS);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            break;
                        }
                    }
                }

                if (!dancePlaybackStopped) {
                    String extremesSummary = robotController.getExtremesSummary();
                    runOnUiThread(() -> {
                        stopTimer();
                        statusText.setText("Feature playback finished\n" + extremesSummary);
                    });
                }
            });
            return;
        }

        List<VideoAnalyzer.DanceStep> steps = latestDanceSteps;
        if (steps == null || steps.isEmpty()) {
            statusText.setText("No analyzed features or dance steps available");
            return;
        }

        // Step1：先歸位
        robotController.home(statusListener);

        // Step2：等待歸位完成後再開始舞蹈
        executorService.execute(() -> {
            try {
                Thread.sleep(motionStepMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }

            // Step3：建立事件到 motion 的對應表
            List<String> motions = robotController.getMotionList();
            if (motions == null || motions.isEmpty()) {
                runOnUiThread(() -> statusText.setText("No motion found on this Kebbi"));
                return;
            }

            Map<String, String> eventToMotion = MotionPoseMap.buildEventToMotionMap(motions);

            // Step4：逐步驟播放舞蹈
            dancePlaybackStopped = false;
            startTimer();
            robotController.beginExtremeTracking();
            statusText.setText("Playing dance steps: " + steps.size());
            executorService.execute(() -> {
                for (int i = 0; i < steps.size(); i++) {
                    if (dancePlaybackStopped) {
                        break;
                    }

                    VideoAnalyzer.DanceStep step = steps.get(i);
                    String motionName = eventToMotion.get(step.eventType);
                    if (motionName == null) {
                        final int stepNumber = i + 1;
                        runOnUiThread(() -> statusText.setText(
                                "Dance " + stepNumber + " / " + steps.size()
                                        + "\n" + step.eventType
                                        + "\nNo mapped motion"));
                        continue;
                    }
                    int stepNumber = i + 1;
                    runOnUiThread(() -> statusText.setText(
                            "Dance " + stepNumber + " / " + steps.size()
                                    + "\n" + step.eventType
                                    + "\nPlaying: " + motionName));
                    robotController.motionPlay(motionName, false);

                    // Step5：等待動作完成，同時輪詢馬達位置記錄實際範圍
                    long deadline = System.currentTimeMillis() + motionStepMs;
                    while (System.currentTimeMillis() < deadline && !dancePlaybackStopped) {
                        robotController.pollExtremes();
                        try {
                            Thread.sleep(MOTOR_POLL_INTERVAL_MS);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            break;
                        }
                    }
                }

                // Step6：播放完成後更新 UI（含馬達實際範圍摘要）
                if (!dancePlaybackStopped) {
                    String extremesSummary = robotController.getExtremesSummary();
                    runOnUiThread(() -> {
                        stopTimer();
                        statusText.setText("Dance playback finished\n" + extremesSummary);
                    });
                }
            });
        });
    }

    /**
     * 測試 RobotMapper：把「最新分析結果的 PoseFeature」映射成指令並在機器人播放。
     *
     * <p>若尚未分析影片，則用 RobotMapper.buildSamples() 的樣本姿勢頂替。
     * 先把映射結果顯示在 UI，再依序在機器人上實際播放。
     */
    private void testRobotMapper() {
        RobotMapper mapper = new RobotMapper();

        // Step1：決定資料來源（優先使用真實分析結果，其次樣本姿勢）
        final List<PoseFeature> features;
        if (latestFeatures != null && !latestFeatures.isEmpty()) {
            features = latestFeatures;
        } else {
            List<RobotMapper.SamplePose> samples = RobotMapper.buildSamples();
            List<PoseFeature> fallback = new java.util.ArrayList<>();
            for (RobotMapper.SamplePose sample : samples) {
                fallback.add(sample.feature);
            }
            features = fallback;
        }

        // Step2：映射所有特徵並顯示結果
        StringBuilder report = new StringBuilder("=== RobotMapper Test ===\n");
        int show = Math.min(features.size(), COMMAND_PREVIEW_FRAMES);
        for (int i = 0; i < show; i++) {
            report.append('\n').append("frame ").append(i).append(": ")
                    .append(features.get(i).describe()).append('\n');
            report.append(RobotMapper.describeCommands(mapper.map(features.get(i))));
        }
        report.append('\n').append("Total frames to play: ").append(features.size());
        runOnUiThread(() -> statusText.setText(report.toString()));

        // Step3：在機器人上依序播放
        if (!robotController.isReady()) {
            statusText.setText(report.toString() + "\n\n(未播放：Nuwa SDK 未就緒)");
            return;
        }
        executorService.execute(() -> {
            for (int i = 0; i < features.size(); i++) {
                if (Thread.currentThread().isInterrupted()) {
                    break;
                }
                PoseFeature feature = features.get(i);
                // 遮擋幀直接跳過，不要讓機器人擺出根本沒發生過的姿勢
                if (!feature.confident) {
                    continue;
                }
                List<RobotCommand> commands = mapper.map(feature);
                final int frameNumber = i;
                runOnUiThread(() -> statusText.setText("Playing frame " + frameNumber
                        + " / " + features.size()));
                // 播放節奏由 executeRobotCommands 依「這一步要轉幾度」自己算，
                // 這裡不再額外 sleep(motionStepMs)。
                // 舊版每幀多等 1.2 秒，50 幀的 10 秒影片要播 7 分鐘以上。
                robotController.executeRobotCommands(commands, statusListener);
                if (Thread.currentThread().isInterrupted()) {
                    break;
                }
            }
            runOnUiThread(() -> statusText.setText("RobotMapper playback finished"));
        });
    }

    /**
     * 產生「最新分析特徵 → RobotCommand」的預覽字串。
     *
     * @return 前幾幀的映射摘要；無資料時回傳空字串
     */
    private String commandPreview() {
        if (latestFeatures.isEmpty()) {
            return "";
        }
        RobotMapper mapper = new RobotMapper();
        StringBuilder sb = new StringBuilder("\n---- RobotCommand preview ----\n");
        int show = Math.min(latestFeatures.size(), COMMAND_PREVIEW_FRAMES);
        for (int i = 0; i < show; i++) {
            sb.append("frame ").append(i).append(":\n");
            sb.append(RobotMapper.describeCommands(mapper.map(latestFeatures.get(i))));
        }
        return sb.toString();
    }

    /**
     * Preview the keyframe table for quick verification.
     */
    private String keyframePreview() {
        if (latestKeyframes == null || latestKeyframes.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder("\n---- Keyframe preview ----\n");
        int show = Math.min(latestKeyframes.size(), COMMAND_PREVIEW_FRAMES);
        for (int i = 0; i < show; i++) {
            sb.append(latestKeyframes.get(i).describe()).append('\n');
        }
        return sb.toString();
    }

    /**
     * 停止當前播放的動作和 TTS，並將計時器歸零。
     */
    private void stopMotion() {
        dancePlaybackStopped = true;
        resetTimer();
        if (scriptPlayer != null) {
            scriptPlayer.stop();
        }
        if (robotController != null) {
            robotController.stopAll();
        }
        statusText.setText("Stopped");
    }

    /**
     * 計時器歸零：停止計時並把顯示清回 00:00.0。
     */
    private void resetTimer() {
        isTimerRunning = false;
        timerHandler.removeCallbacks(timerRunnable);
        if (timerText != null) {
            timerText.setText("00:00.0");
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();

        // Step1：關閉 VideoAnalyzer（含內部 MediaPipe Pose Landmarker）
        if (videoAnalyzer != null) {
            videoAnalyzer.close();
        }

        // Step2：關閉執行緒池
        if (executorService != null) {
            executorService.shutdownNow();
        }

        // Step3：停止計時器、遠端接收與腳本播放
        stopTimer();
        if (scriptServer != null) {
            scriptServer.stop();
        }
        if (scriptPlayer != null) {
            scriptPlayer.shutdown();
        }

        // Step4：釋放 Nuwa SDK 資源
        if (robotController != null) {
            robotController.release();
        }
    }

    /**
     * 開始計時器。
     */
    private void startTimer() {
        danceStartTime = System.currentTimeMillis();
        isTimerRunning = true;
        timerText.setText("00:00.0");
        timerHandler.post(timerRunnable);
    }

    /**
     * 停止計時器。
     */
    private void stopTimer() {
        isTimerRunning = false;
        timerHandler.removeCallbacks(timerRunnable);
    }

    /**
     * 計時器更新 Runnable。
     */
    private final Runnable timerRunnable = new Runnable() {
        @Override
        public void run() {
            if (!isTimerRunning) {
                return;
            }
            long elapsed = System.currentTimeMillis() - danceStartTime;
            long seconds = elapsed / 1000;
            long millis = (elapsed % 1000) / 100;
            long minutes = seconds / 60;
            long secs = seconds % 60;
            timerText.setText(String.format(Locale.US, "%02d:%02d.%d", minutes, secs, millis));
            timerHandler.postDelayed(this, TIMER_UPDATE_INTERVAL_MS);
        }
    };

}
