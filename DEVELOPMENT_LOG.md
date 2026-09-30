# 開發紀錄

> 專案：Kebbi 看影片學跳舞專題
> 專案位置：`C:\kebbi`
> 更新時間：2026-09-24

---

## 2026-09-24（馬達軸向/方向/範圍依官方資料修正；移除 web_sim）

### 目標
用 NUWA 官方資料解掉「馬達軸向未確認」與「極限值是猜的」兩個 TODO。

### 依據
- NuwaUnity SDK「Motor angle range table」＝ NUWA 網頁模擬器 hardware.xml 編碼器範圍（僅 NECK_Z 文件 ±40、編碼器 ±31，取 ±31）。
- NUWA 模擬器 robot.xml：neck_z 轉軸 (0 0 1)＝左右轉，neck_y (0 1 0)＝點頭。
- 220 支官方動作：`666_TA_LookLR` neck_z 先 +21（左）；`666_TA_LookDnU` neck_y 先 +9（低頭）；`666_RE_HiL` 左肩 -131、左肘 -62（抬手、彎肘都是負值）。

### 這次完成
- `RobotMotor`：`NECK_YAW = NECK_Z`、`NECK_PITCH = NECK_Y`（原本相反）；補上各馬達方向與範圍註解。
- `RobotMapper`：範圍改為官方不對稱範圍（頭 ±31/±20、肩 -200~70、肘 -80~0）；頭 pitch、肩、肘改為反號；`ARM_SCALE 0.45→1.0`、`ELBOW_SCALE 0.45→0.6`（依「機器人範圍 / 人體範圍」原則重算）；`maxDegFor` 改為 `minDegFor` + `maxDegFor`。
  - 舊版送出的指令在真機上：頭左右轉會變成點頭、抬手會往後擺、彎肘的正值會被夾成 0（手肘永遠不彎）。
- `DanceScriptPlayer.sanitize()`：改用不對稱範圍夾位。
- `RobotController.testHeadTurningLimits()`：yaw 改測 NECK_Z、pitch 改測 NECK_Y。
- 移除 `web_sim/`（不太可用）。舊版仍在 git 歷史（commit fd15977）可找回。

### 驗證
- 純 Java 類別（make_dance.py 同一組）javac 編譯通過；`buildSamples()` 映射：both_hands_up → 肩 -140、肘 -33；head_right_rh_up → NECK_Z 31；pitch_down_lh_up → NECK_Y 20。
- `RobotController` 需要 Android SDK，這台沒有，未編譯（只對調 NECK_Y / NECK_Z 兩個常數與標籤）；請在 Android Studio 跑一次 `assembleDebug`。

### 備註
- 仍建議實機按一次 Test Head Limits，確認 NECK_Z 是左右轉。
- 目前人體「抬手角」（任何方向）對應機器人 SHOULDER_Y（往前上舉）；側舉若要更像，可改用 SHOULDER_X（側向外展 -3~100）。

---

## 2026-08-11 update 2（Keyframe 微調 + 播放/歸位修正）

### 目標
把「秒表被 NEUTRAL 吃掉」和「頭部幅度太小」兩個主要問題拉正，並補齊 Stop Motion / Home 的操作行為。

### 這次完成
- `VideoAnalyzer.buildKeyframes()` 改用「非 NEUTRAL 優先」選取：一秒內只要有任何非 `NEUTRAL` 樣本，就忽略該秒的 NEUTRAL 票數，避免 4 個 neutral 蓋掉 1 個有效動作。
- 事件門檻放寬（`VideoAnalyzer` / `PoseAnalyzer` 同步）：
  - 手高於肩膀：`0.08f -> 0.05f`
  - 身體傾斜：`0.05f -> 0.03f`
- `RobotMapper` 頭部縮放加大，讓頭部動作更明顯：
  - `HEAD_YAW_SCALE = 1.35f`
  - `HEAD_PITCH_SCALE = 1.4f`
- `MainActivity.playKeyframeMotion()` 優先吃 `keyframe.robotMotion`：若已是實際 motion 名就直接播放，否則才走 event→motion 對應。
- Stop Motion 按鈕：計時器歸零（`resetTimer()` 清回 `00:00.0`）。
- Home 按鈕：找不到 `home` motion 時，改用直接馬達控制把 `TRACKED_MOTORS` 全部移回 0°，確保一定歸位（關機後結尾動作）。

### 驗證
- `gradlew assembleDebug` BUILD SUCCESSFUL。
- 三份 md 已同步更新（KEBBI_DANCE_PROJECT / FUNCTION_REFERENCE / DEVELOPMENT_LOG）。

### 備註
- 程式已編譯，實際效果仍需實機再跑一次確認（新 runtime 的 `dance_keyframes.csv` 尚未取得）。
- 下一步可整理「這版還要再調的參數清單」（門檻 / scale / clamp）供後續微調。

---

## 2026-08-11 Keyframe Table v1

### 目標
把播放流程從逐幀特徵，改成每 1 秒一筆的關鍵幀表，先穩定完成「做什麼、什麼時候做」。

### 這次完成
- `VideoAnalyzer` 新增 `KeyframeEntry` 與秒級 keyframe table 產生流程。
- 分析結果新增 `dance_keyframes.csv`，欄位包含 `second_index / time_ms / event_type / robot_motion / amplitude_percent / sample_count`。
- `MainActivity` 播放按鈕改成優先播放 `latestKeyframes`，播完最後一筆後主動停止。
- 分析完成後先顯示 keyframe preview，方便直接檢查秒級表。
- 保留 feature / dance step fallback，避免 keyframe 尚未產生時流程中斷。

### 目前策略
- 先完成秒級對照表，再補秒與秒之間的過渡。
- robot motion 仍由 runtime 的 motion list 對應，keyframe 表保留可手動調整的 event 與 amplitude 資訊。
---

## 2026-08-11 更新

### 本次整理
- 已把播放主線改成秒級 `KeyframeEntry`，並維持舊的 feature / dance step fallback。
- 已新增 `dance_keyframes.csv`，方便後續手動微調每秒事件與幅度。
- 已確認 APK 可用本機 JDK + Gradle 成功建置，並能在裝置上自動分析與播放。

### 觀察到的問題
- 前 10 秒 keyframe 仍偏向 `NEUTRAL`，代表事件偵測門檻還偏保守。
- 目前播放雖可自動結束，但幅度仍偏小，尤其是頭部與手臂。

### 下一步
- 調低 keyframe 事件門檻。
- 放大 `RobotMapper` 的輸出幅度。
- 讓 `dance_keyframes.csv` 成為主要的人工微調入口。

---

## 2026-08-07 收斂架構：建立 package + 串通分析→RobotMapper→RobotController 資料流

### 目標
不再新增功能，開始收斂架構：
1. 建立真正 package（model / math / analysis / robot / io / camera）。
2. MainActivity 只留 UI 與流程編排（控制在 300~400 行），不再碰 MediaPipe / Camera / CSV / PoseMath。
3. 把整條資料流串通：影片 → PoseFrame → PoseAnalyzer → PoseFeature → RobotMapper → RobotCommand → RobotController。

### 完成狀態
- ✅ 建立 6 個 package 並搬移 20 個檔案（改 package 宣告 + 跨 package import）：
  - `model/`：Landmark, PoseFrame, MotionSequence, MotionSource, PoseLandmark, PoseFeature, RobotCommand
  - `math/`：PoseMath, PoseMathVerify
  - `analysis/`：PoseAnalyzer, VideoAnalyzer
  - `robot/`：RobotController, RobotMapper, RobotMotor, MotionPoseMap
  - `io/`：PoseIO, PosePipeline
  - `camera/`：CameraController, CameraTestActivity
- ✅ `VideoAnalyzer`：改為自行建立/關閉 MediaPipe PoseLandmarker（`VideoAnalyzer(Context, ExecutorService)`）；`ResultListener.onAnalyzed` 改為 `(String summary, List<PoseFeature> features, List<DanceStep> danceSteps)`，分析結果回傳 PoseFeature 列表供 RobotMapper 使用。
- ✅ `MainActivity`：移除 MediaPipe 欄位與 `setupPoseLandmarker()`；新增 `latestFeatures` 欄位；分析完成後顯示 RobotCommand 預覽（`commandPreview()`）；`testRobotMapper()` 改為優先播放最新分析結果的 PoseFeature（無結果才用樣本姿勢）。目前 402 行。
- ✅ `CameraTestActivity`：每幀 PoseFeature → RobotMapper.map() → Log RobotCommand（先只印出，之後開 execute）。
- ✅ BUILD SUCCESSFUL（`gradlew assembleDebug`）。
- ✅ 三份 md 更新（FUNCTION_REFERENCE 加入 package 結構與新資料流）。

### 備註
- MainActivity 只負責：按按鈕 → VideoAnalyzer（拿 PoseFeature）→ RobotMapper（拿 RobotCommand）→ RobotController（執行）。
- Camera 流程已串到「印出 RobotCommand」，之後只要把 execute 打開就會動。
- 下一步（尚未動工）：依實機結果調 RobotMapper 的 scale/clamp；P5 MotionComparator（相似度評分）。

---

## 2026-08-07 實作 RobotMapper（P3：PoseFeature → RobotCommand）

### 目標
依 roadmap 的 P3 里程碑，實作「人體特徵 → 機器人指令」的純 Java 映射層，接上 `RobotController` 直接馬達控制，並加一顆測試按鈕方便實機驗證映射參數。

### 完成狀態
- ✅ 新增 `RobotMotor.java`：10 個馬達索引常數（純 Java，值與 `NuwaRobotAPI.MOTOR_*` 一致）。
- ✅ 新增 `RobotCommand.java`：單一馬達指令 POJO（motorId / degree / durationSeconds + describe()）。
- ✅ 新增 `RobotMapper.java`：`map(PoseFeature)` → 6 個 `RobotCommand`（頭 yaw / 頭 pitch / 左右肩 Y / 左右肘 Y），scale 與 clamp 均為可調常數；`buildSamples()` 產生 4 組樣本姿勢（neutral / both_hands_up / head_right_rh_up / pitch_fwd_lh_up）供測試；內含 `SamplePose` 類別。
- ✅ `RobotController.java`：新增 `executeRobotCommands(List<RobotCommand>, StatusListener)`，依序 ctlMotor 播放；`TRACKED_MOTORS` 改用 `RobotMotor` 常數。
- ✅ `MainActivity.java`：新增「Test RobotMapper」按鈕（layout + strings + `testRobotMapper()`），先顯示每組樣本映射結果，再依序在機器人實際播放。
- ✅ BUILD SUCCESSFUL（`gradlew assembleDebug`）。
- ✅ 三份 md 更新。

### 備註
- 映射 scale/clamp 為初版猜測值，需實機播放後依 `Test Head Limits` 結果微調。
- 測試仍由使用者實機執行（本輪不跑 adb）。
- 下一步（尚未動工）：P4 Camera 即時模式、P5 MotionComparator（相似度評分）。

---

## 2026-08-07 拆 CameraController + VideoAnalyzer

### 目標
依規劃的拆檔順序繼續瘦身 `MainActivity`（RobotController 之後）：先拆 Camera、再拆 VideoAnalyzer，讓 MainActivity 只剩 UI 與流程編排（~360 行），為接下來的 RobotMapper 鋪路。

### 完成狀態
- ✅ 新增 `CameraController.java`：Camera2 開啟/關閉、預覽啟動/停止、`grabPreview()` 抓幀、內部 HandlerThread；`StatusListener` + `PreviewReadyListener` 介面。
- ✅ 重寫 `CameraTestActivity.java`：相機操作改委派 `CameraController`，本類只保留 MediaPipe 偵測迴圈（每 200ms `grabPreview` → `detectForVideo` → `toPoseFrame` → `PoseAnalyzer.analyzeFrame` → `Log.i`）、`toPoseFrame`、權限請求、`onDestroy` 釋放。
- ✅ 新增 `VideoAnalyzer.java`：`ResultListener` 介面（`onAnalyzed(summary, danceSteps)` / `onError(message)`）+ 公開 static `DanceStep`（timeMs / eventType）；`analyzeBuiltInClip(ResultListener)`（前 10 秒、CSV、每幀 Log、角度 min~max 範圍統計、danceSteps=null）、`analyzeDanceVideo(Uri, ResultListener)`（CSV + motion plan 寫入、`PosePipeline.verify` round-trip、舞蹈事件偵測、回傳 danceSteps）；私有 `toPoseFrame` / `writeLandmarks` / `detectDanceEvent` / `isVisible`。
- ✅ `MainActivity.java`：~700 → 361 行。移除舊版 `analyzeBuiltInClip()` / `analyzeDanceVideo()` / `toPoseFrame()` / `writeLandmarks()` / `detectDanceEvent()` / `isVisible()` 與私有 `DanceStep` 類別；新增 `videoAnalyzer` / `latestDanceSteps` / `analyzeListener` 欄位；按鈕與影片選擇器改呼叫 `videoAnalyzer.analyzeBuiltInClip / analyzeDanceVideo(analyzeListener)`；`playDanceMotion()` 改用 `List<VideoAnalyzer.DanceStep>`。
- ✅ BUILD SUCCESSFUL（`gradlew assembleDebug`）。
- ✅ 三份 md 更新（FUNCTION_REFERENCE 補 CameraController / VideoAnalyzer 區段與新 Call Graph）。

### 備註
- 測試仍由使用者實機執行（本輪不跑 adb）。
- 下一步（尚未動工）：P4 Camera 即時模式、P5 MotionComparator（相似度評分）。

---

## 2026-08-07 拆 RobotController + 直接馬達控制

### 目標
1. 拆出 `RobotController`，把 Nuwa SDK、motion、TTS、極限測試、馬達範圍追蹤從 `MainActivity` 移出（降低 MainActivity 行數，方便後續加 RobotMapper）。
2. 確認 Nuwa SDK 支援直接馬達控制並實作「逐度頭部極限測試」。
3. 跳舞播放時追蹤馬達實際角度 max/min。
4. 影片分析加入 Debug 可視化（Log + 範圍統計）。

### SDK 調查結果（javap 反組譯）
- `NuwaRobotAPI.getInst()` 為公開 static singleton，constructor 都會寫入 `gRobot`，所以 `RobotController` 初始化後一定可取到實例。
- `public void ctlMotor(int motorId, float degree, float duration)`：直接控制馬達（degree 單位），內部走 `mSendAIDLHandler` → SendHandler(param_1..param_n) 送給機器人服務。
- `public float getMotorPresentPositionInDegree(int)`：同步讀回目前馬達角度。
- `lockMotor(int[,int[,float]])` / `getlockMotor(int)` / `motionSeek(float)` / `motionCurrentPosition()` / `motionTotalDuration()` 亦存在。
- 馬達常數：`MOTOR_NECK_Y=1`（yaw）、`MOTOR_NECK_Z=2`（pitch）、雙肩/雙肘 Y/X/Z 各 3~10。

### 完成狀態
- ✅ 新增 `RobotController.java`：SDK 初始化 / `isReady()` / `getMotionList()` / `motionPlay()` / `stopAll()`（motionStop + stopTTS）/ `home(StatusListener)` / `testHeadTurningLimits()`（ctlMotor 逐度 yaw 0→90、pitch 0→30，讀回實際角度偵測卡住，取不到 `getInst()` 時退回 motion 測試）/ `testHandWavingLimits()` / `beginExtremeTracking()` / `pollExtremes()` / `getExtremesSummary()`。
- ✅ `MainActivity.java`：1123 → ~700 行。移除 Nuwa SDK 相關 import/欄位/方法，改由 `robotController` 委派；按鈕直接接 `robotController.home/testHeadTurningLimits/testHandWavingLimits(statusListener)`；舞蹈播放迴圈改用 `robotController.motionPlay / pollExtremes / getExtremesSummary`；同時清除死碼（`pickDanceVideo`、`playDanceButton` 欄位、`UPPER_BODY`、`MOTION_STEP_MS`、重複 import）。
- ✅ 跳舞播放時追蹤馬達範圍：播放迴圈每 500ms `pollExtremes()`，結束顯示 `M%d: min ~ max deg`。
- ✅ Debug 可視化：`analyzeBuiltInClip()` 每幀 `Log.i("PoseDebug", f.describe())`，摘要增加 L肘/R肘/肩/頭 的 min~max 範圍。
- ✅ BUILD SUCCESSFUL（`gradlew assembleDebug`）。

### 備註
- 測試仍由使用者實機執行（本輪不跑 adb）。
- 下一步（尚未動工）：P4 Camera 即時模式、P5 MotionComparator（相似度評分）。

---

## 2026-08-04 註解加註工作

### 目標
依照規範逐一為 Java 檔案加註解：
1. 每個 class 前加入 JavaDoc（用途、誰會呼叫、不負責什麼）
2. 每個 public method 前加入 JavaDoc（功能、參數、回傳值、什麼時候呼叫）
3. 每個複雜邏輯區塊加入 `// Step1 // Step2` 註解
4. 不刪除任何原本程式
5. 不重構
6. 每完成一個檔案就停止，等待確認

### 完成狀態

| 檔案 | 狀態 |
|---|---|
| MotionSource.java | ✅ 完成 |
| PoseLandmark.java | ✅ 完成 |
| Landmark.java | ✅ 完成 |
| PoseFrame.java | ✅ 完成 |
| MotionSequence.java | ✅ 完成 |
| PoseFeature.java | ✅ 完成 |
| PoseMath.java | ✅ 完成 |
| PoseIO.java | ✅ 完成 |
| PoseAnalyzer.java | ✅ 完成 |
| PosePipeline.java | ✅ 完成 |
| MotionPoseMap.java | ✅ 完成 |
| MainActivity.java | ✅ 完成 |
| CameraTestActivity.java | ✅ 完成（類別 JavaDoc） |

### Git 安全點
- Commit: `5da1a00` - "safety point: before PoseMath verification work"
- Commit: `599f38d` - "feat: add PoseAnalyzer print button and documentation"
- Commit: `93f0d0c` - "feat: analyzeBuiltInClip writes CSV for PoseAnalyzer results"
- Commit: `a0e6b9a` - "fix: initialize poseAnalyzeButton with findViewById"

### Bug 修復
- `poseAnalyzeButton` 未被 `findViewById` 初始化，導致 App 啟動時 crash
- 已修正：添加 `poseAnalyzeButton = findViewById(R.id.poseAnalyzeButton);`

---

## 2026-08-04 連接 PoseAnalyzer 資料管道

### 目標
將 `PoseAnalyzer.analyzeFrame()` 接到 `CameraTestActivity` 的每一幀，
讓 MediaPipe → PoseFrame → PoseAnalyzer → PoseFeature → Log 的資料流真正串起來。

### 完成狀態
- ✅ `CameraTestActivity.java`：修改 `runFrameLoop()`，捕獲 `PoseLandmarkerResult`
- ✅ `CameraTestActivity.java`：新增 `toPoseFrame()` 方法，將 MediaPipe `NormalizedLandmark` 轉為專案內部 `Landmark`
- ✅ `CameraTestActivity.java`：每幀呼叫 `PoseAnalyzer.analyzeFrame()` 並 `Log.i(TAG, feature.describe())`
- ✅ `activity_main.xml`：補上缺失的 `testRobotButton`（前次 commit 遺漏）
- ✅ BUILD SUCCESSFUL
- ✅ Git commit: `0810c11`
 
---

## 2026-08-11 update 3

- `VideoAnalyzer` keyframe detection was upgraded to score-based matching instead of strict boolean checks.
- Arm lift is now recognized from `leftArmAngle` / `rightArmAngle`, so raised arms can become `LEFT_HAND_UP`, `RIGHT_HAND_UP`, or `BOTH_HANDS_UP` even when the wrist is not fully above the shoulder line.
- `PoseAnalyzer` now has the same arm-angle fallback, so `inferredEventType` and keyframe output stay closer to each other.
- The build was re-run successfully after the changes.

---

## 2026-09-24 PC Offload：電腦端產生舞蹈腳本

- 新增 `pc/`：`extract_pose.py`（MediaPipe 抽點）、`make_dance.py`（一鍵流程）、`DanceScriptGenerator`（重用 App 純 Java 類別）。用法見 `pc/README.md`。
- App 新增 `model/DanceScript`、`io/DanceScriptIO`、`robot/DanceScriptBuilder`；`PoseIO.JsonParser` 改 package-private。
- 實測 20 秒影片 → 83 步 / 344 指令，JSON round-trip OK。

## 2026-09-24 PC Offload：APK 腳本播放 + Wi-Fi 接收

- 新增 `robot/DanceScriptPlayer`、`net/ScriptServer`；`MainActivity` 加「播放腳本」「遠端接收」按鈕；Manifest 加 `INTERNET`。
- 電腦端 `pc/send_script.py` 傳送 / 控制，`FakeKebbi` 無機器人測試。使用方式見 `pc/README.md`。
- FakeKebbi 端到端測試通過；`assembleDebug` BUILD SUCCESSFUL。
