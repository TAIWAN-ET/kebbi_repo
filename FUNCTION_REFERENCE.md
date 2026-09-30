# FUNCTION_REFERENCE.md

> Kebbi 看影片學跳舞專題 — 函式參考手冊
> 最後更新：2026-08-11

---

## 2026-08-11 Update 2（Keyframe 微調）

- `VideoAnalyzer` now writes `dance_keyframes.csv` using a non-`NEUTRAL`-first selection rule for each second.
- `PoseAnalyzer` and `VideoAnalyzer.detectDanceEvent()` now share looser thresholds for wrist raise and torso lean.
- `RobotMapper` head movement scaling was increased so `headYaw` and `torsoLean` produce more visible motor movement.
- `MainActivity.playKeyframeMotion()` now prefers an already resolved `robotMotion` value from the keyframe table before falling back to event-to-motion mapping.

## 2026-08-11 Update（Keyframe Table 引入）

- Playback now prefers second-based `KeyframeEntry` tables from `VideoAnalyzer`.
- `MainActivity` consumes `latestKeyframes` first and falls back to pose features or dance steps.
- `dance_keyframes.csv` is exported for manual inspection and tuning.
- Playback stops after the final keyframe and calls `stopAll()`.

---

## Package 結構（2026-08-07 重構）

```
com.example.myapplication
├── MainActivity.java      # UI + 流程編排（~400 行，只負責按按鈕 → 串流程）
├── model/                 # 純 Java POJO（零 Android/MediaPipe import）
│   ├── Landmark.java
│   ├── PoseFrame.java
│   ├── MotionSequence.java
│   ├── MotionSource.java
│   ├── PoseLandmark.java
│   ├── PoseFeature.java
│   ├── RobotCommand.java
│   └── KeyframeEntry.java
├── math/                  # 純 Java 數學
│   ├── PoseMath.java
│   └── PoseMathVerify.java  # PC 端驗證 main
├── analysis/              # 姿態分析
│   ├── PoseAnalyzer.java
│   └── VideoAnalyzer.java   # 自行建立 PoseLandmarker，回傳 PoseFeature
├── robot/                 # 機器人層
│   ├── RobotController.java
│   ├── RobotMapper.java
│   ├── RobotMotor.java
│   └── MotionPoseMap.java
├── io/                    # 資料序列化
│   ├── PoseIO.java
│   └── PosePipeline.java
└── camera/                # 相機層
    ├── CameraController.java
    └── CameraTestActivity.java
```

### 主要資料流

```
影片
  ↓ MediaPipe PoseLandmarker（VideoAnalyzer 內部）
PoseFrame
  ↓ PoseAnalyzer.analyzeFrame()
PoseFeature
  ↓ RobotMapper.map()
List<RobotCommand>
  ↓ RobotController.executeRobotCommands()（ctlMotor）
Kebbi
```

---

## 機器人控制層

### RobotController
**位置**：`robot/RobotController.java`

**用途**：封裝 Kebbi 機器人控制（Nuwa SDK 初始化、motion 播放/停止、TTS、歸位、極限測試、跳舞時馬達範圍追蹤）。`MainActivity` 只負責 UI 與流程編排，所有機器人動作指令都在這裡。

**依賴**：`ApiManager` / `NuwaRobotManager` / `NuwaVoiceManager` / `NuwaRobotAPI`（`NuwaRobotAPI.getInst()` 為公開 singleton，`ctlMotor(int motor, float degree, float speed)` 直接控制馬達（**第三參數是速度，單位度/秒**，SDK 內部走 `setSpeedInDegreePerSec`），`getMotorPresentPositionInDegree(int)` 讀回角度）。

**介面**
| 方法 | 參數 | 回傳 | 說明 |
|---|---|---|---|
| `RobotController(Context)` | Activity/Application Context | - | 初始化 Nuwa SDK（順序：ApiManager → NuwaRobotManager → NuwaVoiceManager） |
| `isReady()` | - | `boolean` | SDK 是否已初始化 |
| `release()` | - | - | 釋放 SDK 資源並關閉內部執行緒池 |
| `getMotionList()` | - | `List<String>` | 機器人內建 motion 清單 |
| `setMotionStepMs(int)` | 間隔毫秒 | - | 設定動作播放間隔（UI SeekBar 同步） |
| `motionPlay(String, boolean)` | 名稱 + 是否循環 | - | 播放 motion |
| `stopAll()` | - | - | 停止 motion 與 TTS |
| `home(StatusListener)` | 狀態回呼 | - | 歸位：優先用名稱含 "home" 的 motion；找不到時用直接馬達控制把 `TRACKED_MOTORS` 全部移回 0°（關機後結尾動作） |
| `testHeadTurningLimits(StatusListener)` | 狀態回呼 | - | 直接馬達逐度測頭部極限（yaw 0→90、pitch 0→30，讀回實際角度偵測卡住；取不到 API 實例則退回 motion 測試） |
| `testHandWavingLimits(StatusListener)` | 狀態回呼 | - | 找 "hand"/"wave" motion 依序播放測手部極限 |
| `beginExtremeTracking()` | - | - | 開始追蹤馬達角度範圍（跳舞播放前呼叫） |
| `pollExtremes()` | - | - | 輪詢追蹤馬達角度（播放迴圈內每 500ms 呼叫） |
| `getExtremesSummary()` | - | `String` | 各馬達 min~max 摘要 |
| `executeRobotCommands(List<RobotCommand>, StatusListener)` | 指令列表 + 狀態回呼 | - | **一次送出整組** ctlMotor 讓六顆馬達同時動，再依「轉最多的那顆」估算等待時間統一等一次（RobotMotor 常數與 NuwaRobotAPI.MOTOR_* 數值一致） |

**StatusListener 介面**
| 方法 | 說明 |
|---|---|
| `onStatus(String)` | UI 層用它把訊息顯示到 statusText（MainActivity 內以 `runOnUiThread` 包裝） |

---

## RobotMapper 層

### RobotMotor
**位置**：`robot/RobotMotor.java`

**用途**：機器人馬達索引常數（純 Java，不依賴 SDK）。數值與 `NuwaRobotAPI.MOTOR_*` 一致，由 `RobotController` 對應。

**常數**
| 常數 | 值 | 說明 |
|---|---|---|
| NECK_Y | 1 | 頭部左右轉（yaw） |
| NECK_Z | 2 | 頭部上下動（pitch） |
| RIGHT_SHOULDER_Z | 3 | 右肩 Z（外展） |
| RIGHT_SHOULDER_Y | 4 | 右肩 Y（舉/放） |
| RIGHT_SHOULDER_X | 5 | 右肩 X（前/後擺） |
| RIGHT_ELBOW_Y | 6 | 右肘 Y（彎/伸） |
| LEFT_SHOULDER_Z | 7 | 左肩 Z（外展） |
| LEFT_SHOULDER_Y | 8 | 左肩 Y（舉/放） |
| LEFT_SHOULDER_X | 9 | 左肩 X（前/後擺） |
| LEFT_ELBOW_Y | 10 | 左肘 Y（彎/伸） |

---

### RobotCommand
**位置**：`model/RobotCommand.java`

**用途**：單一馬達動作指令 POJO（純 Java）。由 `RobotMapper.map()` 產生，`RobotController` 執行。

**欄位**
| 欄位 | 類型 | 說明 |
|---|---|---|
| motorId | int | 馬達 id（`RobotMotor` 常數） |
| degree | float | 目標角度（度） |
| speedDegPerSec | float | 移動**速度**（度/秒），直接對應 `ctlMotor` 第三參數 |

**方法**
| 方法 | 回傳 | 說明 |
|---|---|---|
| `describe()` | `String` | 格式如 "M8: 45 deg / 1.0s" |

---

### RobotMapper
**位置**：`robot/RobotMapper.java`

**用途**：`PoseFeature`（人體資訊）→ `List<RobotCommand>`（機器人指令）的映射器，絕不認識 MediaPipe/Landmark。純 Java。

**對應原則**（scale 是可調常數；範圍取自 NuwaUnity SDK 角度表 / NUWA 模擬器 hardware.xml，方向取自官方動作檔）：
- 頭部 yaw × 1.35 → `NECK_YAW` = `NECK_Z`（-31 ~ 31，正＝機器人往自己左邊轉）
- 頭部 pitch × 1.4，**反號** → `NECK_PITCH` = `NECK_Y`（-20 ~ 20，正＝低頭）
- 左/右手臂角（扣休息 20°）× 1.0，**反號** → `LEFT/RIGHT_SHOULDER_Y`（-200 ~ 70，負＝往前上舉）
- 左/右肘角（175° 減之）× 0.6，**反號** → `LEFT/RIGHT_ELBOW_Y`（-80 ~ 0，負＝彎肘）
- `minDegFor(id)` / `maxDegFor(id)`：各馬達範圍，`DanceScriptPlayer.sanitize()` 用來在機器人端再夾一次

**方法**
| 方法 | 參數 | 回傳 | 說明 |
|---|---|---|---|
| `map(PoseFeature)` | 人體特徵 | `List<RobotCommand>` | 映射成 6 個馬達指令 |
| `buildSamples()` | - | `List<SamplePose>` | 產生樣本姿勢（neutral / both_hands_up / head_right_rh_up / pitch_fwd_lh_up）供測試鈕使用 |
| `describeCommands(List<RobotCommand>)` | 指令列表 | `String` | 每指令一行的摘要 |

**SamplePose（public static 內部類別）**
| 欄位 | 說明 |
|---|---|
| name | 樣本名稱 |
| feature | 樣本人體特徵 |
| `describe()` | 名稱 + 特徵描述 |

---

## 資料層（Data Layer）

### Landmark
**位置**：`model/Landmark.java`

**用途**：單一人體關節點的 POJO，儲存 x, y, z, visibility, presence。

**欄位**
| 欄位 | 說明 |
|---|---|
| x | 畫面歸一化 X 座標 (0.0 ~ 1.0) |
| y | 畫面歸一化 Y 座標 (0.0 ~ 1.0) |
| z | 深度座標 |
| visibility | 可見程度 (0.0 ~ 1.0) |
| presence | 存在信心值 (0.0 ~ 1.0) |

**建構子**
- `Landmark()` — 無參，供 JSON 反序列化
- `Landmark(float x, float y, float z, float visibility, float presence)` — 帶完整資料

---

### PoseFrame
**位置**：`model/PoseFrame.java`

**用途**：單一影格的人體姿態，包含 33 個 Landmark。

**欄位**
| 欄位 | 說明 |
|---|---|
| frameIndex | 影格序號 |
| timestampMs | 時間戳記（毫秒） |
| landmarks | 33 個 Landmark 的列表 |

**方法**
| 方法 | 回傳 | 說明 |
|---|---|---|
| `getLandmark(int id)` | `Landmark` | 取得指定編號的關節點 |

---

### MotionSequence
**位置**：`model/MotionSequence.java`

**用途**：一段完整的動作序列，由多個 PoseFrame 組成。

**欄位**
| 欄位 | 說明 |
|---|---|
| source | 動作來源 (`MotionSource`) |
| fps | 取樣幀率 |
| frames | 所有 PoseFrame |

**方法**
| 方法 | 回傳 | 說明 |
|---|---|---|
| `getFrameCount()` | `int` | 影格總數 |
| `getLandmarkCount()` | `int` | 每幀 Landmark 數量（應為 33） |
| `describe()` | `String` | 簡短描述字串 |

---

### MotionSource
**位置**：`model/MotionSource.java`

**用途**：動作序列來源的 enum（VIDEO / CAMERA / JSON）。

**值**
| 值 | 說明 |
|---|---|
| VIDEO | 來自影片檔案 |
| CAMERA | 來自即時攝影機 |
| JSON | 來自 JSON 檔案 |

---

### PoseLandmark
**位置**：`model/PoseLandmark.java`

**用途**：MediaPipe Pose Landmarker 的 33 個 landmark 索引常數，消滅魔術數字。

**常數**
| 常數 | 值 | 說明 |
|---|---|---|
| NOSE | 0 | 鼻子 |
| LEFT_SHOULDER | 11 | 左肩 |
| RIGHT_SHOULDER | 12 | 右肩 |
| LEFT_ELBOW | 13 | 左肘 |
| RIGHT_ELBOW | 14 | 右肘 |
| LEFT_WRIST | 15 | 左腕 |
| RIGHT_WRIST | 16 | 右腕 |
| LEFT_HIP | 23 | 左髖 |
| RIGHT_HIP | 24 | 右髖 |
| COUNT | 33 | Landmark 總數 |

---

### PoseFeature
**位置**：`model/PoseFeature.java`

**用途**：人體動作特徵（角度、傾斜、手是否舉高等），是「人體資訊」不是「Robot 指令」。

**欄位**
| 欄位 | 類型 | 說明 |
|---|---|---|
| leftElbowAngle | float | 左肘角度（度） |
| rightElbowAngle | float | 右肘角度（度） |
| leftArmAngle | float | 左手臂**抬升**角（髖-肩-肘，度）：下垂約 20、平舉約 90、高舉約 170 |
| rightArmAngle | float | 右手臂角度（度） |
| leftHipAngle | float | 左髋角度（度） |
| rightHipAngle | float | 右髋角度（度） |
| shoulderSlope | float | 肩膀傾斜角（度） |
| torsoLean | float | 軀幹**左右側傾**角（度，正=往畫面右倒）。不是前傾，不要拿來當頭部 pitch |
| headYaw | float | 头部偏转角（度） |
| leftWristAboveShoulder | boolean | 左手腕是否高於肩膀 |
| rightWristAboveShoulder | boolean | 右手腕是否高於肩膀 |
| bodyLean | float | 軀幹左右傾斜量 |
| inferredEventType | String | 推導出的姿勢事件類型 |

**方法**
| 方法 | 回傳 | 說明 |
|---|---|---|
| `describe()` | `String` | 可讀描述字串 |

---

### KeyframeEntry
**位置**：`model/KeyframeEntry.java`

**用途**：秒級 keyframe table 的資料類，由 `VideoAnalyzer.buildKeyframes()` 產生，供 `MainActivity` 播放與人工檢查/微調。

**欄位**
| 欄位 | 類型 | 說明 |
|---|---|---|
| `secondIndex` | int | 第幾秒 |
| `timeMs` | long | 該秒起點時間（毫秒） |
| `eventType` | String | 該秒最佳事件（BOTH_HANDS_UP / RIGHT_HAND_UP / LEFT_HAND_UP / LEAN_LEFT / LEAN_RIGHT / NEUTRAL） |
| `robotMotion` | String | 已解析的 motion 名稱（可直接播放；未解析時與 eventType 相同或空白） |
| `amplitudePercent` | float | 幅度百分比（0~100） |
| `sampleCount` | int | 該秒內取樣幀數 |

**方法**
| 方法 | 回傳 | 說明 |
|---|---|---|
| `describe()` | `String` | 摘要字串（含秒、事件、motion、幅度） |

---

## 數學工具層

### PoseMath
**位置**：`math/PoseMath.java`

**用途**：純 Java 的姿態數學工具，不依賴 Android 或 MediaPipe。

**方法**
| 方法 | 參數 | 回傳 | 說明 |
|---|---|---|---|
| `calculateAngle(Landmark a, Landmark b, Landmark c)` | 三點（b 為頂點） | `float` | 三點夾角（度） |
| `calculateDistance(Landmark a, Landmark b)` | 兩點 | `float` | 三維歐氏距離 |
| `calculateMidPoint(Landmark a, Landmark b)` | 兩點 | `Landmark` | 中點 |
| `normalize(Landmark lm, Landmark origin)` | 點 + 原點 | `Landmark` | 座標平移 |
| `lowPass(Landmark current, Landmark previous, float alpha)` | 當前值 + 上一幀 + 係數 | `Landmark` | 一階低通濾波 |
| `isConfident(Landmark lm, float threshold)` | 關節點 + 閾值 | `boolean` | 判斷是否可信 |

---

## I/O 層

### PoseIO
**位置**：`io/PoseIO.java`

**用途**：CSV / JSON 與 MotionSequence 之間的雙向轉換。純 Java，不依賴 Android。

**方法**
| 方法 | 參數 | 回傳 | 說明 |
|---|---|---|---|
| `writeCsv(File, MotionSequence)` | 檔案 + 序列 | `void` | 寫入 CSV |
| `readCsv(File)` | 檔案 | `MotionSequence` | 從 CSV 讀取 |
| `writeJson(File, MotionSequence)` | 檔案 + 序列 | `void` | 寫入 JSON |
| `readJson(File)` | 檔案 | `MotionSequence` | 從 JSON 讀取 |
| `toJsonString(MotionSequence)` | 序列 | `String` | 轉為 JSON 字串 |
| `fromJsonString(String)` | JSON 字串 | `MotionSequence` | 從 JSON 字串解析 |

---

## 分析層

### PoseAnalyzer
**位置**：`analysis/PoseAnalyzer.java`

**用途**：將 MotionSequence 轉換成 PoseFeature 序列。純 Java，不依賴 Android。

**方法**
| 方法 | 參數 | 回傳 | 說明 |
|---|---|---|---|
| `analyzeFrame(PoseFrame)` | 單一影格 | `PoseFeature` | 分析單幀的人體特徵 |
| `analyzeSequence(MotionSequence)` | 動作序列 | `List<PoseFeature>` | 分析整段序列 |
| `inferEventType(boolean, boolean, float)` | 左手舉、右手舉、身體傾斜 | `String` | 推導姿勢事件類型 |

---

### VideoAnalyzer
**位置**：`analysis/VideoAnalyzer.java`

**用途**：把「影片 → MediaPipe Pose Landmarker → PoseFrame → PoseAnalyzer → 舞蹈事件偵測 → CSV/Log 摘要」全部封裝起來，讓 `MainActivity` 只負責 UI 與流程編排。之後換模型（MoveNet / OpenPose）也只改這層。PoseLandmarker 由本類自行建立與關閉。

**依賴**：`Context` / `ExecutorService` / `MediaMetadataRetriever` / `PoseAnalyzer` / `PosePipeline`。

**介面**
| 方法 | 參數 | 回傳 | 說明 |
|---|---|---|---|
| `VideoAnalyzer(Context, ExecutorService)` | Context + 執行緒池 | - | 建立分析器（內部自行建立 MediaPipe PoseLandmarker） |
| `analyzeBuiltInClip(ResultListener)` | 結果回呼 | - | 分析內建影片（dance_input.mp4）前 10 秒，含每幀 Log 與角度 min~max 範圍統計，回傳 summary + PoseFeature + keyframes（danceSteps=null） |
| `analyzeDanceVideo(Uri, ResultListener)` | 影片 Uri + 結果回呼 | - | 逐幀解析 → CSV → 舞蹈事件偵測 → round-trip 驗證 → 秒級 keyframe table，回傳 summary + PoseFeature + danceSteps + keyframes |
| `close()` | - | - | 釋放內部 MediaPipe PoseLandmarker |

**ResultListener 介面**
| 方法 | 說明 |
|---|---|
| `onAnalyzed(String, List<PoseFeature>, List<DanceStep>, List<KeyframeEntry>)` | 分析完成（summary 顯示用；features 供 RobotMapper 映射；keyframes 供 Keyframe 播放；內建影片分析時 danceSteps 為 null） |
| `onError(String)` | 分析失敗 |

**DanceStep（public static 內部類別）**
| 欄位 | 類型 | 說明 |
|---|---|---|
| `timeMs` | long | 事件發生時間（毫秒） |
| `eventType` | String | 事件類型（LEFT_HAND_UP / RIGHT_HAND_UP / BOTH_HANDS_UP / LEAN_LEFT / LEAN_RIGHT） |

**私有方法**
| 方法 | 說明 |
|---|---|
| `toPoseFrame(long, List<NormalizedLandmark>)` | MediaPipe landmark → 內部 PoseFrame |
| `writeLandmarks(FileWriter, long, List<List<NormalizedLandmark>>)` | 寫入 landmark CSV |
| `detectDanceEvent(List<NormalizedLandmark>)` | 由 landmark 推導舞蹈事件類型 |
| `buildKeyframes(List<PoseFeature>, List<Long>, long)` | 依秒統計事件 → 產生 `List<KeyframeEntry>`（非 NEUTRAL 優先，排除 NEUTRAL 吞噬有效動作） |
| `writeKeyframes(FileWriter, List<KeyframeEntry>)` | 輸出 `dance_keyframes.csv` |
| `resolveKeyframeEvent(PoseFeature)` | 由單一 PoseFeature 推導穩定 keyframe 事件 |
| `estimateKeyframeAmplitude(PoseFeature, String)` | 估算事件幅度百分比 |
| `clampPercent(float)` | 幅度限制在 0~100 |
| `isVisible(NormalizedLandmark)` | landmark 可見性判斷 |

---

### PosePipeline
**位置**：`io/PosePipeline.java`

**用途**：資料層協調者，提供 round-trip 驗驗證。純 Java，不依賴 Android。

**方法**
| 方法 | 參數 | 回傳 | 說明 |
|---|---|---|---|
| `verify(File csvFile, File jsonFile)` | CSV 檔案 + JSON 檔案 | `String` | 執行 round-trip 驗證 |

---

### MotionPoseMap
**位置**：`robot/MotionPoseMap.java`

**用途**：機器人內建 motion 與姿勢事件類型的對應表。

**方法**
| 方法 | 參數 | 回傳 | 說明 |
|---|---|---|---|
| `buildEventToMotionMap(List<String>)` | motion 清單 | `Map<String,String>` | 建立事件→動作查表 |
| `eventForMotion(List<String>, String)` | motion 清單 + motion 名稱 | `String` | 查詢 motion 對應的事件 |

---

## UI 層

### MainActivity
**位置**：`MainActivity.java`（~400 行）

**用途**：主 Activity，提供 UI 與流程編排。Nuwa SDK 相關呼叫已委派給 `RobotController`、影片分析委派給 `VideoAnalyzer`、PoseFeature→指令委派給 `RobotMapper`。不再碰 MediaPipe / Camera / CSV / PoseMath。

**方法**
| 方法 | 說明 |
|---|---|
| `onCreate(Bundle)` | 初始化 RobotController、VideoAnalyzer、計時器、UI 按鈕 |
| `setupVideoPicker()` | 註冊影片選擇器，選完呼叫 `videoAnalyzer.analyzeDanceVideo(uri, listener)` |
| `playKeyframeMotion()` | 主要播放：吃 `latestKeyframes` 秒級表格，先歸位再依秒播放（`robotMotion` 已解析則直接用，否則經 event→motion 對應；NEUTRAL 顯示停留；播完 `stopAll()`） |
| `playDanceMotion()` | 後備播放：無 keyframe 時吃 `latestFeatures` / `latestDanceSteps` |
| `testRobotMapper()` | 把最新分析結果的 PoseFeature（或樣本姿勢）映射成 RobotCommand 並依序播放 |
| `commandPreview()` | 產生分析結果前幾幀的 RobotCommand 預覽字串 |
| `keyframePreview()` | 產生 keyframe table 前幾筆的預覽字串 |
| `stopMotion()` | 停止動作和 TTS（委派 `RobotController.stopAll()`），並把計時器歸零 |
| `resetTimer()` | 計時器歸零：停止 tick 並把顯示清回 `00:00.0` |
| `startTimer()` / `stopTimer()` | 螢幕計時器開始/停止 |

**欄位/回呼**
| 項目 | 說明 |
|---|---|
| `robotController` | 機器人控制委派（`RobotController`） |
| `videoAnalyzer` | 影片分析委派（`VideoAnalyzer`，自行管理 MediaPipe） |
| `latestDanceSteps` | 最近一次分析的 `List<VideoAnalyzer.DanceStep>` |
| `latestKeyframes` | 最近一次分析的 `List<KeyframeEntry>`（秒級表格，優先播放） |
| `latestFeatures` | 最近一次分析的 `List<PoseFeature>`（供 RobotMapper 映射） |
| `statusListener` | `RobotController.StatusListener`，把訊息顯示到 statusText |
| `analyzeListener` | `VideoAnalyzer.ResultListener`，顯示摘要 + Keyframe/RobotCommand 預覽，保存特徵、舞蹈步驟與 keyframes |

---

## 相機層

### CameraController
**位置**：`camera/CameraController.java`

**用途**：封裝 Camera2 的開啟/關閉、預覽啟動/停止與抓幀，讓 `CameraTestActivity` 只專注在 MediaPipe 姿態偵測。

**依賴**：`TextureView` / `CameraManager`（`getCameraIdList()[0]`）/ HandlerThread。

**介面**
| 方法 | 參數 | 回傳 | 說明 |
|---|---|---|---|
| `CameraController(TextureView, PreviewReadyListener, StatusListener)` | 預覽 View + 預覽就緒回呼 + 狀態回呼 | - | 建立控制器並開啟相機 |
| `start()` | - | - | 開啟相機並啟動預覽 |
| `postDelayed(Runnable, long)` | 任務 + 延遲 | - | 委派給相機 HandlerThread |
| `isCameraOpen()` | - | `boolean` | 相機是否已開啟 |
| `grabPreview()` | - | `Bitmap` | 從預覽 TextureView 抓取目前畫面 |
| `close()` | - | - | 關閉相機與 HandlerThread |

**StatusListener / PreviewReadyListener 介面**
| 方法 | 說明 |
|---|---|
| `onStatus(String)` | 狀態訊息（權限失敗、開啟失敗等） |
| `onPreviewReady()` | 預覽已就緒，可開始抓幀迴圈 |

---

### CameraTestActivity
**位置**：`camera/CameraTestActivity.java`

**用途**：Camera + MediaPipe 姿態偵測煙霧測試。相機操作已委派給 `CameraController`，本類只負責 MediaPipe 偵測迴圈與權限。

**方法**
| 方法 | 說明 |
|---|---|
| `onCreate(Bundle)` | 初始化 CameraController、PoseLandmarker、權限 |
| `onRequestPermissionsResult(...)` | 處理相機權限結果 |
| `setupPoseLandmarker()` | 建立 MediaPipe Pose Landmarker（VIDEO 模式） |
| `runFrameLoop()` | 每 200ms `grabPreview()` → `detectForVideo` → `toPoseFrame` → `PoseAnalyzer.analyzeFrame` → `Log.i(TAG, feature.describe())` |
| `toPoseFrame(long, List)` | MediaPipe landmark → 內部 PoseFrame |
| `onDestroy()` | 關閉 CameraController 與 PoseLandmarker |

---

## Call Graph

```
MainActivity
│
├── analyzeBuiltInClip 按鈕
│      │
│      ▼
│   VideoAnalyzer.analyzeBuiltInClip(listener)
│      │
│      ▼
│   PoseAnalyzer.analyzeFrame(PoseFrame)
│   VideoAnalyzer.buildKeyframes() → KeyframeEntry
│      ▼
│   PoseFeature + KeyframeEntry（回傳至 analyzeListener）
│      ▼
│   RobotMapper.map() → RobotCommand preview（顯示）
│
├── setupVideoPicker()
│      │
│      ▼
│   VideoAnalyzer.analyzeDanceVideo(uri, listener)
│      │
│      ▼
│   PoseAnalyzer.analyzeFrame(PoseFrame)
│   PosePipeline.verify(csvFile, jsonFile)
│   VideoAnalyzer.buildKeyframes() → KeyframeEntry
│      ▼
│   PoseFeature + DanceStep + KeyframeEntry（回傳至 analyzeListener）
│
├── playKeyframeMotion()（主要）
│      │
│      ▼
│   RobotController.home()
│   MotionPoseMap.buildEventToMotionMap()
│   KeyframeEntry.robotMotion（已解析直接用，否則 event→motion 對應）
│   RobotController.motionPlay() / stopAll()
│   RobotController.beginExtremeTracking()
│   RobotController.pollExtremes()
│   RobotController.getExtremesSummary()
│
├── playDanceMotion()（後備）
│      │
│      ▼
│   MotionPoseMap.buildEventToMotionMap()
│   RobotController.home()
│   RobotController.motionPlay()
│   RobotController.beginExtremeTracking()
│   RobotController.pollExtremes()
│   RobotController.getExtremesSummary()
│
├── testRobotMapper()
│      │
│      ▼
│   RobotMapper.map(PoseFeature) → List<RobotCommand>
│   RobotController.executeRobotCommands()
│
├── stopMotion()
│      │
│      ▼
│   RobotController.stopAll()
│
├── home / Head Limits / Hand Limits 按鈕
│      │
│      ▼
│   RobotController.home()
│   RobotController.testHeadTurningLimits()
│   RobotController.testHandWavingLimits()
│
└── CameraTestActivity
       │
       ▼
    CameraController
    PoseLandmarker.detectForVideo()
    PoseAnalyzer.analyzeFrame() → PoseFeature
    RobotMapper.map() → Log RobotCommand

VideoAnalyzer
│
├── analyzeBuiltInClip()
│   analyzeDanceVideo()
│      │
│      ▼
│   MediaMetadataRetriever.getFrameAtTime()
│   PoseLandmarker.detectForVideo()
│   toPoseFrame()
│   PoseAnalyzer.analyzeFrame() → PoseFeature（收集回傳）
│   detectDanceEvent() → DanceStep
│   writeLandmarks() → CSV
│   PosePipeline.verify()
│   buildKeyframes() → KeyframeEntry → writeKeyframes() → dance_keyframes.csv

CameraController
│
├── start()
│      │
│      ▼
│   CameraManager.openCamera()
│   createCaptureSession() → Preview
│
├── grabPreview()
│      │
│      ▼
│   previewTexture.getBitmap()
│
└── close()
       │
       ▼
    CameraDevice.close()
    HandlerThread.quitSafely()

RobotController
│
├── home()
├── testHeadTurningLimits()
│      │
│      ▼
│   NuwaRobotAPI.getInst()
│   NuwaRobotAPI.ctlMotor(motorId, degree, speedDegPerSec)
│   NuwaRobotAPI.getMotorPresentPositionInDegree(motorId)
│
├── testHandWavingLimits()
│      │
│      ▼
│   NuwaRobotManager.motionPlay()
│
├── executeRobotCommands(List<RobotCommand>)
│      │
│      ▼
│   NuwaRobotAPI.ctlMotor(motorId, degree, speedDegPerSec)
│
└── stopAll()
       │
       ├── NuwaRobotManager.motionStop(true)
       └── NuwaVoiceManager.stopTTS()

RobotMapper
│
└── map(PoseFeature)
       │
       ▼
    RobotCommand（NECK_Y / NECK_Z / 左右肩Y / 左右肘Y）
       │
       ▼
    RobotController.executeRobotCommands()
```

```
PoseIO
│
├── readCsv() → MotionSequence
│      │
│      ▼
│   PoseFrame[] (每幀 33 個 Landmark)
│
├── writeCsv() ← MotionSequence
│
├── readJson() → MotionSequence
│
└── writeJson() ← MotionSequence

PoseAnalyzer
│
├── analyzeFrame(PoseFrame) → PoseFeature
│      │
│      ▼
│   PoseMath.calculateAngle() × 6
│   PoseMath.calculateDistance() × 2
│   PoseMath.calculateMidPoint() × 3
│
└── analyzeSequence(MotionSequence) → List<PoseFeature>

PosePipeline
│
└── verify(csv, json) → String
     │
     ├── PoseIO.readCsv() → MotionSequence
     ├── PoseIO.writeJson()
     ├── PoseIO.readJson() → MotionSequence
     └── verifyEquality() → 報告字串
```

---

## 資料流程

```
影片 / Camera
    │
    ▼
MediaPipe Pose Landmarker
    │ 輸出 NormalizedLandmark (33 點)
    ▼
VideoAnalyzer.toPoseFrame()  (影片)
CameraTestActivity.toPoseFrame()  (Camera)
    │ 轉換為 PoseFrame (33 個 Landmark)
    ▼
PoseAnalyzer.analyzeFrame()
    │ 計算角度/距離/傾斜
    ▼
PoseFeature (人體動作特徵)
    │
    ├──→ PoseIO.writeCsv() / writeJson() (儲存)
    ├──→ PosePipeline.verify() (驗證)
    ├──→ VideoAnalyzer.detectDanceEvent() (舞蹈事件 → DanceStep)
    ├──→ VideoAnalyzer.buildKeyframes() (秒級 KeyframeEntry → dance_keyframes.csv)
    ├──→ MotionPoseMap (事件類型對照)
    └──→ RobotMapper.map() → RobotCommand → RobotController.executeRobotCommands() (Kebbi)

MainActivity 播放：
    latestKeyframes（優先）→ 每秒一筆 → robotMotion 已解析則直接播放，
    否則 event→motion 對應；NEUTRAL 停留；播完 stopAll()
    └ 無 keyframes → latestFeatures（RobotMapper）→ latestDanceSteps（後備）
```
## 2026-08-11 update 3

- `VideoAnalyzer.resolveKeyframeEvent()` now uses a score-based selector, not a strict threshold-only classifier.
- `VideoAnalyzer.estimateKeyframeAmplitude()` now reuses those scores so the CSV amplitude is closer to the actual gesture strength.
- `PoseAnalyzer.analyzeFrame()` now falls back to arm-angle inference when wrist-based inference is empty.
