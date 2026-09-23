# Kebbi 看影片學跳舞專題 — 架構與進度總覽

> 文件性質：把舊有三份操作/日誌 txt 與目前程式碼架構整合為單一總覽。
> 最後更新：2026-08-07
> 專案位置：`C:\kebbi`
> APK：`C:\kebbi\app\build\outputs\apk\debug\app-debug.apk`
> App package：`com.example.myapplication`

---

## 一、專題目標

讓 Nuwa Air-H202 / Kebbi 機器人「看影片（或即時影像）學跳舞」。

硬體限制（決定架構走向）：
- 機器人硬碟只有 **10GB**，CPU 弱。
- 全部用 **Java / Android** 實作。
- 因此：**機器人只接收動作指令，姿態辨識不在 Kebbi 上跑重模型**。P0 採 on-device VIDEO 模式驗證；P1 再評估改為 PC Offload。

三個長期方向（共用同一條資料管線）：
1. 學習端資訊傳回電腦判斷再傳回去的即時跳舞。
2. 錄入影片在極小空間內完成精準學習。
3. 最難的：即時學習並成功跳舞。

核心結論：**不管影片或 Camera，最後都輸出同一份 `MotionSequence`（PoseFrame 陣列）**，
後面的播放、評分、機器人控制全部共用，不用做兩套。

---

## 二、目前已完成（實測 OK）

來源：舊 `kebbi_dance_project_current_status.txt` / `kebbi_dance_project_code_notes.txt` 日誌。

| 項目 | 狀態 | 備註 |
|---|---|---|
| Nuwa SDK 初始化順序修正 | ✅ | 必須先 `ApiManager.init()`，再 `NuwaRobotManager` / `NuwaVoiceManager` |
| Kebbi 說話（TTS） | ✅ | `voiceManager.startTTS("Hello, I am Kebbi.")` 實測進 `tts_speak_start` |
| Kebbi 播放內建 motion | ✅ | `Test Built-in Motions` 5 支 motion 均進 `play_start` |
| Kebbi 停止 motion / TTS | ✅ | `robotManager.motionStop(true)` + `voiceManager.stopTTS()` |
| MediaPipe Pose Landmarker（VIDEO 模式） | ✅ | 每 200ms 抽 frame，輸出 33 個 landmark |
| 影片 → 33 點 landmark → CSV | ✅ | `dance_pose_landmarks.csv` |
| 10 支測試影片 | ✅ | `C:\kebbi\video_push\dream_01~10.mp4` 可直接驗證 |
| 錄影/Camera 即時數據化 | ❌ | 即為下一階段 |
| PoseFeature → Robot 指令映射（RobotMapper） | ✅ | P3 完成：PoseFeature → RobotCommand → ctlMotor（不做 33 點直連） |
| 秒級 Keyframe Table 播放 | ✅ | P3.5：每秒統計事件 → `dance_keyframes.csv` → `MainActivity` 表格驅動播放 |
| 動作評分/相似度 | ❌ | 研究加分 |

實測 5 支內建 motion（logcat 確認）：
`000_P4_MoveS500`、`888_ML_Petdonkey_24`、`888_ML_Haveidea_20`、`888_ML_HugFox_22`、`001_K11_TutorialV3085`

---

## 三、目前程式碼架構（Data Layer 已成型 + Package 化）

舊版問題：`MainActivity` 同時負責 UI、CSV、JSON、Round-trip，難維護。
現已抽出獨立分層並拆成 package：

```
MainActivity (Android UI + 流程編排，機器人/相機/影片分析已抽出)
   │
   ├── robot.RobotController   ← Nuwa SDK / motion / TTS / 極限測試 / 馬達範圍追蹤
   ├── camera.CameraController ← Camera2 開關 / 預覽 / 抓幀
   ├── analysis.VideoAnalyzer  ← 影片 → MediaPipe → PoseFrame → PoseFeature（自行管理 Landmarker）
   │
   └── 只呼叫
       ▼
io.PosePipeline          ← 協調 + 強化驗證（verify）
   │
   ▼
io.PoseIO                ← CSV / JSON 讀寫（純 Java facade）
   │
   ▼
model.Landmark / PoseFrame / MotionSequence / MotionSource / PoseLandmark  ← 純 Java POJO
```

`MainActivity` 現在只做：
- UI 按鈕綁定與流程編排。
- 按鈕/影片選擇器委派給 `analysis.VideoAnalyzer`（分析內建影片、分析舞蹈影片），拿到 `PoseFeature` 列表。
- 把 `PoseFeature` 交給 `robot.RobotMapper.map()` 得到 `RobotCommand`，再交給 `robot.RobotController.executeRobotCommands()` 執行。
- `camera.CameraTestActivity` 委派相機操作給 `CameraController`，每幀特徵經 `RobotMapper` 轉成指令印 Log。

### 核心 POJO（100% 純 Java，零 Android import，PC / 單元測試可重用）

| 類別 | 職責 |
|---|---|
| `Landmark` | 單一關節點：`x, y, z, visibility, presence`（mutable public field，便於 JSON 函式庫反序列化） |
| `PoseFrame` | 一幀姿態：`frameIndex, timestampMs, List<Landmark>`，提供 `getLandmark(int id)` |
| `MotionSequence` | 整段舞蹈：`source(MotionSource), fps, List<PoseFrame>`，提供 `getFrameCount() / getLandmarkCount() / describe()` |
| `MotionSource` | enum：`VIDEO / CAMERA / JSON`（取代易拼錯的 String） |
| `PoseLandmark` | MediaPipe 33 點索引常數（如 `NOSE=0, LEFT_SHOULDER=11, RIGHT_WRIST=16...`），消滅魔術數字 |
| `KeyframeEntry` | 秒級 keyframe：`secondIndex, timeMs, eventType, robotMotion, amplitudePercent, sampleCount` |

### PoseIO（資料 I/O facade）
- `readCsv(File)` / `writeCsv(File, MotionSequence)`
- `readJson(File)` / `writeJson(File, MotionSequence)` / `toJsonString` / `fromJsonString`
- CSV 解析使用 `TreeMap<Integer, Landmark>` 依 `landmarkIndex` 定位，即使 CSV 行順序錯亂也能正確升冪還原。
- JSON 使用自寫極簡解析器（純 Java，不依賴 Android `org.json` 或 Gson）。

### PosePipeline（協調 + 驗證）
- `verify(File csv, File json)`：執行 `CSV → MotionSequence → JSON → MotionSequence` round-trip，
  並做嚴格比對：
  - frameCount 是否一致
  - landmarkCount 是否一致（應為 33）
  - 每幀 landmark 數量是否一致
  - 抽查 frame0 的 nose / 左肩 / 右肩座標（容差 1e-4f）

### 檔案地圖（2026-08-07 package 化）
```
C:\kebbi\app\src\main\java\com\example\myapplication\
├── MainActivity.java        # Android UI + 流程編排（只做 按鈕→VideoAnalyzer→RobotMapper→RobotController）
├── model/                   # 純 Java POJO
│   ├── Landmark.java        # 單一關節點
│   ├── PoseFrame.java       # 一幀姿態
│   ├── MotionSequence.java  # 整段舞蹈
│   ├── MotionSource.java    # enum VIDEO/CAMERA/JSON
│   ├── PoseLandmark.java    # 33 點索引常數
│   ├── PoseFeature.java     # 人體動作特徵（RobotMapper 只讀這層）
│   ├── RobotCommand.java    # 單一馬達指令 POJO
│   └── KeyframeEntry.java   # 秒級 Keyframe table 資料（secondIndex/timeMs/eventType/robotMotion/amplitude）
├── math/
│   ├── PoseMath.java        # 角度/距離/中點/正規化/濾波
│   └── PoseMathVerify.java  # PC 端驗證 main
├── analysis/
│   ├── PoseAnalyzer.java    # PoseFrame → PoseFeature
│   └── VideoAnalyzer.java   # 影片→MediaPipe→PoseFeature（自行管理 Landmarker + DanceStep）
├── robot/
│   ├── RobotController.java # Nuwa SDK / motion / TTS / 極限測試 / 馬達範圍追蹤 / executeRobotCommands
│   ├── RobotMapper.java     # PoseFeature → List<RobotCommand>（純 Java 映射，含 SamplePose）
│   ├── RobotMotor.java      # 馬達索引常數（值與 NuwaRobotAPI.MOTOR_* 一致）
│   └── MotionPoseMap.java   # 事件類型 → motion 對應
├── io/
│   ├── PoseIO.java          # CSV/JSON I/O
│   └── PosePipeline.java    # round-trip 驗證
└── camera/
    ├── CameraController.java # Camera2 開關 / 預覽 / 抓幀
    └── CameraTestActivity.java # Camera + MediaPipe 煙霧測試（每幀 PoseFeature→RobotCommand 印 Log）
```

---

## 四、Round-trip 驗證（P0-A 已完成）

影片分析完成後，`MainActivity` 呼叫 `PosePipeline.verify(...)`：
```
CSV（33 點/幀） → MotionSequence → JSON → MotionSequence
```
畫面顯示：
```
PoseFrame round-trip: OK
  frameCount: 120 vs 120  OK
  landmarkCount: 33 vs 33  OK
  per-frame landmark count: OK
  spot-check frame0 (nose/L-shoulder/R-shoulder) coords: OK
JSON: /sdcard/.../dance_pose_landmarks.json
```
代表「影片已被數據化」且 JSON 能完整還原，之後不用重跑 MediaPipe。

PC 端純 Java 獨立測試已確認：
- 完整 33 點序列：round-trip 全 `OK`。
- 亂序 CSV（輸入 `24,0,12,11,16,15,23`）：還原後順序正確為 `0,11,12,15,16,23,24`。

---

## 五、Code Review 重點（歷次）

1. **PoseIO 抽出**：`MainActivity` 不再直接處理 CSV/JSON，是真正的 Data Layer。
2. **enum MotionSource**：避免 `"video"/"VIDEO"/"vedio"` 拼寫漂移。
3. **PoseLandmark 常數**：`frame.getLandmark(PoseLandmark.LEFT_SHOULDER)` 可讀性遠高於 `landmarks.get(11)`。
4. **Landmark 改 mutable**：便於 Gson 等函式庫反序列化。
5. **CSV 不依賴順序**：改用 `TreeMap` 依 `landmarkIndex` 定位。
6. **Round-trip 不只比 frameCount**：加 landmarkCount、每幀數量、座標抽查。
7. **Landmark 拆 visibility/presence**：不再合併成單一 `score`，避免混淆。
8. **JSON 不存 frameCount**：由 `frames.size()` 推導，避免不同步。
9. **describe() 歸位**：移到 `MotionSequence`。
10. **MainActivity 只剩 UI**：`runPoseFrameRoundTrip` 移除，邏輯進 `PosePipeline`。

架構評價：⭐⭐⭐⭐⭐ / 驗證完整性：⭐⭐⭐⭐⭐ / 可維護性：⭐⭐⭐⭐⭐（本階段約 9.8/10）。

---

## 六、未來架構 Roadmap（精進版）

原本：`PoseIO → Robot Mapping`
建議改為：

```
Video / Camera
      │
      ▼
   PoseIO                （CSV/JSON ↔ MotionSequence）
      │
      ▼
   PoseMath              （純 Java 數學：角度/距離/中點/正規化/濾波）
      │
      ▼
   PoseAnalyzer          （MotionSequence → PoseFeature）
      │
      ▼
   PoseFeature           （人體動作特徵，非 Robot）
      │
      ▼
   RobotMapper           （PoseFeature → RobotCommand，絕不認識 MediaPipe）✅
      │
      ▼
   RobotCommand
      │
      ▼
   RobotController.executeRobotCommands()（ctlMotor）
      │
      ▼
    Kebbi
```

實際運作（影片）：
```
影片 → VideoAnalyzer（內部 MediaPipe → toPoseFrame）
  → PoseAnalyzer.analyzeFrame → PoseFeature 列表
  → RobotMapper.map() → List<RobotCommand>
  → RobotController.executeRobotCommands() → Kebbi
```

關鍵原則：
- **Robot 不直接讀 33 Landmark**。Robot 只知道 `LeftArmAngle 75°`、`HeadYaw 13°` 這種人體特徵。
- 這樣以後換 OpenPose / MoveNet / BlazePose，只要仍能輸出 `PoseFrame`，後面完全不用改。
- **評分系統直接吃 `PoseFeature`**（左手角度、右手角度、軀幹傾斜、頭部方向），不是比 33 個 Landmark。

### P0 / P1 / P2（沿用）
- **P0（已完成基礎）**：Camera→Pose / Video→Pose 共用 `PoseFrame`；JSON 儲存；round-trip 驗證。
- **P1（加分）**：Camera → Pose → Robot 即時模仿（下面 100% 共用 P0 的 Mapping/Player）。
- **P2（研究）**：一邊看一邊學、建立動作庫（MotionComparator 算相似度，純數學非 AI）。

### 里程碑完成狀態

| 階段 | 內容 | 狀態 |
|---|---|---|
| P0-A | Data Layer（POJO / PoseIO / PosePipeline） | ✅ |
| P0-B | Video Pipeline（影片 → PoseFrame → JSON round-trip） | ✅ |
| P1 | PoseMath（角度 / 距離 / 中點 / 正規化 / 濾波） | ✅ |
| P2 | PoseAnalyzer → PoseFeature（人體動作特徵） | ✅ |
| P3 | RobotMapper（PoseFeature → RobotCommand） | ✅ |
| P3.5 | Keyframe Table（秒級表格驅動播放 + dance_keyframes.csv） | ✅ |
| P4 | Camera 即時模式 | ⏳ |
| P5 | MotionComparator（相似度評分） | ⏳ |

---

## 七、下一個里程碑（先不做 Robot / Camera / 評分）

> P1 / P2 已完成（見里程碑狀態表）。以下保留作為後續擴充 PoseMath / PoseFeature 欄位的參考。

依 Code Review 建議，下一步建立「人體動作特徵」層，而不是直接映射到 Kebbi：

1. **PoseMath**（純 Java）
   - `calculateAngle(a, b, c)`：三點夾角
   - `calculateDistance(a, b)`
   - `calculateMidPoint(a, b)`
   - `normalize(landmark)`
   - `lowPass(landmark, prev)`

2. **PoseAnalyzer**（純 Java）
   - `MotionSequence → PoseFeature`
   - 產出 `LeftArmAngle / RightArmAngle / BodyYaw / HeadPitch` 等

3. **PoseFeature**（純 Java POJO）
   ```java
   class PoseFeature {
       float leftElbowAngle;
       float rightElbowAngle;
       float shoulderSlope;
       float headYaw;
       // ...
   }
   ```
   先做到 Log 輸出：`Left elbow 91° / Right elbow 87° / Head 13°` 即足夠。

完成這層後，機器人控制、動作評分、即時模仿都只是在使用同一份 `PoseFeature`，架構遠比直接從 Landmark 控制穩定。

---

## 八、已知風險 / 待修正（記錄，尚未動工）

| 項目 | 說明 | 建議 |
|---|---|---|
| 自寫 JSON Parser | 維護成本高、易有 bug | Android+Java 可考慮改回 `org.json`；若要跨平台用 Jackson/Gson。目前保留是為了 POJO 純 Java 可重用，屬權衡取捨 |
| TreeMap 缺點檢查 | 若 CSV 缺點，`List` 長度會變短 | 未來補成固定 33 格（缺點填 null 或預設值），不要直接建立 PoseFrame |
| `fps = 0f` | `readCsv()` 目前 fps 為 0 | 建議 `readCsv()` 依 timestamp 計算平均 FPS |
| `MotionSource.VIDEO` 寫死 | `readCsv` 預設 VIDEO 可接受 | `readJson()` 應完全依 JSON 的 source，不預設蓋掉（目前已有 fallback，可接受） |
| `verify()` 回傳 String | UI 耦合顯示格式 | 之後改回傳 `VerifyResult` 物件，UI 再決定怎麼顯示 |
| Shuffle Test 不完整 | 正式測試請固定用完整 33 點資料 | 已用 33 點測試確認 OK |
| ~~MediaPipe 為 2D Landmark~~ | ~~尚未處理人體遮擋（Occlusion）與深度誤差~~ | ✅ 2026-09-06 已處理：角度改用 world landmark（公制），並以 `visibility`/`presence` 產生 `PoseFeature.confident`，遮擋幀不進 keyframe、不轉指令 |
| 馬達軸向未確認 | `MOTOR_NECK_Y` / `MOTOR_NECK_Z` 官方只寫 "neck y" / "neck z"，沒說哪個是 yaw | 實機下 `ctlMotor(NECK_Z, 30, 30)` 看頭是左右轉還是點頭；若相反，只改 `RobotMotor.NECK_YAW/NECK_PITCH` 兩行 |
| 馬達極限值需重測 | 舊的 yaw 90 / pitch 30 是在速度單位錯誤下量到的，不可信 | 修正後重跑 `Test Head Limits` / `Test Hand Limits`，回填 `RobotMapper` 的四個 MAX 常數 |
| event → motion 對應是隨機的 | `MotionPoseMap` 用 "left"/"right"/"both" 比對，但實機 motion 名稱是 `888_ML_Petdonkey_24` 這種，全部命中不了、掉進 fallback 亂配 | 實機逐支播放後人工建立白名單 |

---

## 九、常用指令

```powershell
cd C:\kebbi

# 建置
.\gradlew.bat assembleDebug

# 安裝
adb install -r app\build\outputs\apk\debug\app-debug.apk

# 啟動
adb shell monkey -p com.example.myapplication -c android.intent.category.LAUNCHER 1

# 裝置確認
adb devices

# 拉回資料（分析後在裝置內）
adb pull /sdcard/Android/data/com.example.myapplication/files/dance_pose_landmarks.csv E:\
adb pull /sdcard/Android/data/com.example.myapplication/files/dance_pose_landmarks.json E:\
```

---

## 十、舊 txt 索引（已整合進本文件）

- `kebbi_dance_project_current_status.txt` → 對應本文件 二、三。
- `kebbi_dance_project_code_notes.txt` → 對應本文件 二（變數/函式說明、實測結果）。
- `nuwa_air_h202_apk_flow.txt` → 對應本文件 九（APK 建置/安裝流程）。

---

## 十一、開發紀錄

### 2026-08-07 收斂架構：建立 package + 串通資料流

**目標**：不再新增功能，開始收斂。建立真正 package、MainActivity 只剩 UI 與流程編排、把「影片 → PoseFrame → PoseFeature → RobotCommand → RobotController」整條串通。

**完成狀態**：
- ✅ 6 個 package（model / math / analysis / robot / io / camera）建立並搬移 20 檔。
- ✅ `VideoAnalyzer` 自行管理 MediaPipe PoseLandmarker，`ResultListener.onAnalyzed(summary, features, danceSteps)` 回傳 `List<PoseFeature>`。
- ✅ `MainActivity` 移除 MediaPipe，分析後顯示 RobotCommand 預覽；`testRobotMapper()` 優先播放真實分析特徵（402 行）。
- ✅ `CameraTestActivity` 每幀 PoseFeature → RobotCommand 印 Log。
- ✅ BUILD SUCCESSFUL。

**下一步**：依實機結果調 RobotMapper scale/clamp；P5 MotionComparator。

### 2026-08-07 實作 RobotMapper（P3：PoseFeature → RobotCommand）

**目標**：實作 roadmap P3 的映射層，接上 `RobotController` 直接馬達控制，並加測試按鈕實機驗證。

**完成狀態**：
- ✅ `RobotMotor.java` / `RobotCommand.java` / `RobotMapper.java` 新增（純 Java）。
- ✅ `RobotMapper.map(PoseFeature)` → 6 個 `RobotCommand`（頭 yaw / 頭 pitch / 左右肩 Y / 左右肘 Y）；scale 與 clamp 為可調常數；`buildSamples()` 提供 4 組樣本姿勢。
- ✅ `RobotController.executeRobotCommands(List<RobotCommand>, StatusListener)` 依序 ctlMotor 播放；`TRACKED_MOTORS` 改用 `RobotMotor` 常數。
- ✅ `MainActivity` 新增「Test RobotMapper」按鈕：顯示樣本映射結果 + 依序實機播放。
- ✅ BUILD SUCCESSFUL。
- ✅ 三份 md 更新。

**備註**：映射 scale/clamp 為初版猜測值，需實機依 `Test Head Limits` 結果微調。

**下一步**：P4 Camera 即時模式（已串到印出 RobotCommand）、P5 MotionComparator（相似度評分）。

### 2026-08-07 拆 CameraController + VideoAnalyzer

**目標**：依拆檔順序繼續瘦身 `MainActivity`（RobotController 之後），拆出 Camera 層與影片分析層，讓 MainActivity 只剩 UI 與流程編排（~360 行）。

**完成狀態**：
- ✅ `CameraController.java` 新增：Camera2 開啟/關閉、預覽、`grabPreview()` 抓幀、內部 HandlerThread、`StatusListener` / `PreviewReadyListener`。
- ✅ `CameraTestActivity.java` 重寫：相機操作委派 `CameraController`，只保留 MediaPipe 偵測迴圈與權限。
- ✅ `VideoAnalyzer.java` 新增：`ResultListener` + `DanceStep`；`analyzeBuiltInClip` / `analyzeDanceVideo` 移入；私有 `toPoseFrame` / `writeLandmarks` / `detectDanceEvent` / `isVisible`。
- ✅ `MainActivity` ~700 → 361 行：刪除舊版分析相關方法與私有 `DanceStep`，改由 `videoAnalyzer.analyzeBuiltInClip / analyzeDanceVideo(analyzeListener)` 委派；`playDanceMotion()` 使用 `VideoAnalyzer.DanceStep`。
- ✅ BUILD SUCCESSFUL（`gradlew assembleDebug`）。
- ✅ 三份 md 更新。

**下一步**：RobotMapper（PoseFeature → RobotCommand，P3）。

### 2026-08-07 拆 RobotController + 直接馬達控制

**目標**：拆出 `RobotController`（Nuwa SDK / motion / TTS / 極限測試 / 馬達範圍追蹤），降低 `MainActivity` 行數；確認 SDK 支援直接馬達控制並實作逐度極限測試、跳舞時馬達範圍追蹤、Debug 可視化。

**SDK 調查**（javap）：
- `NuwaRobotAPI.getInst()` 公開 static singleton（constructor 都寫入 `gRobot`）。
- `ctlMotor(int motorId, float degree, float duration)` 直接控制馬達；`getMotorPresentPositionInDegree(int)` 讀回角度；另有 `lockMotor` / `motionSeek` 等。

**完成狀態**：
- ✅ `RobotController.java` 新增（介面見 `FUNCTION_REFERENCE.md`）。
- ✅ `MainActivity` 1123 → ~700 行，機器人指令全部委派給 `RobotController`。
- ✅ 頭部極限測試改為直接 `ctlMotor` 逐度（yaw 0→90、pitch 0→30），讀回實際角度偵測卡住。
- ✅ 跳舞播放時追蹤馬達實際角度 max/min（每 500ms poll）。
- ✅ `analyzeBuiltInClip()` 每幀 `Log.i("PoseDebug")` + 摘要顯示角度範圍。
- ✅ BUILD SUCCESSFUL。

**下一步**：拆 Camera → VideoAnalyzer（已完成）→ RobotMapper（P3，已完成）；P4 Camera 即時模式。

### 2026-08-04 註解加註工作

**目標**：依照規範逐一為 Java 檔案加註解，建立程式文件化。

**規範**：
1. 每個 class 前加入 JavaDoc（用途、誰會呼叫、不負責什麼）
2. 每個 public method 前加入 JavaDoc（功能、參數、回傳值、什麼時候呼叫）
3. 每個複雜邏輯區塊加入 `// Step1 // Step2` 註解
4. 不刪除任何原本程式
5. 不重構
6. 每完成一個檔案就停止，等待確認

**完成狀態**：

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
| CameraTestActivity.java | ✅ 完成 |

**Git 安全點**：
- Commit: `5da1a00` - "safety point: before PoseMath verification work"

**產出文件**：
- `DEVELOPMENT_LOG.md` — 開發紀錄
- `FUNCTION_REFERENCE.md` — 函式參考手冊（含 Call Graph）
---

## 2026-08-11 更新（Keyframe Table 引入）

- 播放流程已改成先產生秒級 keyframe table，再用表格驅動 Kebbi。
- `VideoAnalyzer` 會輸出 `dance_keyframes.csv`。
- `MainActivity` 會優先播放 `latestKeyframes`，播完最後一筆後會自動停止。
- 若 keyframe 尚未準備好，才會退回舊的 feature / dance step 播放。

## 2026-08-11 update 2（Keyframe 微調：NEUTRAL 吞噬 + 頭部幅度）

- Keyframe generation now ignores neutral-majority bias and picks a real action first when any non-`NEUTRAL` sample exists in the second。
- 門檻放寬：手高於肩膀 `0.08→0.05`、身體傾斜 `0.05→0.03`（`VideoAnalyzer` / `PoseAnalyzer` 同步）。
- `RobotMapper` 頭部縮放加大：`HEAD_YAW_SCALE=1.35`、`HEAD_PITCH_SCALE=1.4`，讓頭部動作更明顯。
- `MainActivity` playback 優先吃 `keyframe.robotMotion`（已解析為實際 motion 名就直接用），否則才做 event→motion 對應。
- Stop Motion 按鈕現在會把計時器歸零；Home 在找不到 home motion 時改用直接馬達控制全部歸 0°。
- BUILD SUCCESSFUL 已確認。
## 2026-09-06 依官方文件修正核心 Bug（Batch 1 + 2）

> **接上機器人要做什麼 → 見 `NEXT_STEPS.md`**（S0~S6 驗證步驟、記錄表、調參對照表）


對照 [Nuwa 官方 JavaDoc](https://developer-docs.nuwarobotics.com/sdk/javadoc/reference/com/nuwarobotics/service/agent/NuwaRobotAPI.html)
與 [MediaPipe Pose Landmarker Android 指南](https://developers.google.com/edge/mediapipe/solutions/vision/pose_landmarker/android)
逐項核對，修掉六個會讓「動作看起來不對」的根本原因。

| # | 問題 | 修正 |
|---|---|---|
| 1 | `ctlMotor` 第三參數是**速度（度/秒）**不是時間。舊版傳 1.0～1.5 等於「每秒 1 度」，轉 60 度要 60 秒，但迴圈只等 1.5 秒 → 馬達永遠到不了目標 | `RobotCommand.durationSeconds` → `speedDegPerSec`，預設 60 度/秒；等待時間改成 `角度差 / 速度` |
| 2 | `leftArmAngle` 和 `leftElbowAngle` 是**同一個算式**（肩-肘-腕）→ 肩膀與手肘永遠同步；`normalizedArmLiftScore` 實際在量「手肘有沒有彎」 | 手臂角改為髖-肩-肘；髖角改為肩-髖-膝；抬升分數重新依新尺度定義。**這是 update 2/3 一直調閾值卻調不準的根本原因** |
| 3 | 用 normalized landmark 算角度：x 除以影像寬、y 除以影像高，分母不同 → 角度隨長寬比變形 | `PoseFrame` 新增 `worldLandmarks`，角度一律走 `getAngleLandmarks()`（公制）；畫面相對位置仍用 normalized |
| 4 | 共用一個 VIDEO 模式 `PoseLandmarker`，而每支影片時間戳都從 0 開始 → 第二次分析時間戳倒退，MediaPipe 直接拋例外 | 每次分析建立各自的 landmarker，`finally` 關閉 |
| 5 | `executeRobotCommands` 每顆馬達送完就 sleep → 一個姿勢要 9 秒、關節一個一個動 | 整組一次送出（SDK 是 async AIDL），只等一次；`MainActivity` 不再額外 sleep |
| 6 | 沒有休息姿勢基準：站著不動 → 手肘 175° × 0.5 = 87.5 → clamp 60°，「不動」被映射成「手肘彎 60 度」 | `RobotMapper` 加入 `ARM_REST_DEG` / `ELBOW_REST_DEG`，先扣休息值再乘倍率 |

其他一併處理：
- `KeyframeEntry.robotMotion` 不再複製 `eventType`（舊版害 `MainActivity` 那條分支永遠走不到）。
- `CameraTestActivity` 由 VIDEO 同步 `detectForVideo` 改為官方建議的 LIVE_STREAM + `detectAsync`。
- 加入逐幀低通濾波（`PoseMath.lowPass`，原本寫好了但沒人呼叫）。
- MediaPipe 版本由 `latest.release` 鎖定為 `0.10.29`。
- Manifest 補上 `READ_EXTERNAL_STORAGE`（`maxSdkVersion=32`），因為 minSdk 是 24 而 `READ_MEDIA_VIDEO` 要 API 33。
- `RobotMotor` 新增 `NECK_YAW` / `NECK_PITCH` 語意別名，軸向若實機驗證為相反只需改這兩行。

**驗證**：`gradlew assembleDebug` BUILD SUCCESSFUL；另以 PC 端純 Java 驗證休息姿勢映射為全 0 度、
T 字站姿抬升角 101°、高舉時肩膀 60° 與手肘 25° 不再同步，全部通過。

**尚待實機**：馬達軸向確認、極限值重測、event→motion 白名單（見上方風險表）。

---

## 2026-08-11 update 3

- Keyframe detection now scores arm lift instead of relying only on wrist-above-shoulder checks.
- `PoseAnalyzer` uses the same fallback logic, so the keyframe table and pose summary are less likely to disagree.
- The current goal is still to make each second of video map to a useful action row that can be tuned manually later.

## 2026-09-24 PC Offload 第一步：電腦端產生舞蹈腳本

**目標**：姿態辨識搬到電腦，Kebbi 只負責照腳本播放。這一步先完成「影片 → `dance_script.json`」。

**完成狀態**：
- ✅ `pc/extract_pose.py`：Python + MediaPipe（同一個 `pose_landmarker_lite.task`、同樣 200ms 取樣 / 0.5 門檻），輸出 normalized 與 world 兩份 CSV，格式與 `dance_pose_landmarks.csv` 相同。
- ✅ `pc/make_dance.py`：一鍵執行「抽點 → javac 編譯 App 的純 Java 類別 → 產生腳本」；`--skip-extract` 只重跑映射。
- ✅ `pc/java/.../DanceScriptGenerator.java`：讀 CSV、依時間戳併入 world landmark、低通濾波（α=0.5，同 `VideoAnalyzer`）、寫 JSON 後讀回驗證。
- ✅ App 新增共用類別：`model/DanceScript`（腳本 POJO）、`io/DanceScriptIO`（JSON 讀寫，含版本檢查）、`robot/DanceScriptBuilder`（PoseAnalyzer → RobotMapper → 腳本；速度＝角度差÷取樣間隔，限 10~150°/s；死區 1.5°；不可信幀略過）。
- ✅ `PoseIO.JsonParser` 改為 package-private，給 `DanceScriptIO` 共用。
- ✅ 實測 `video_push/37421255165-1-192.mp4` 前 20 秒：101 幀全部偵測到、13 幀因遮擋略過 → 83 步 / 344 指令，round-trip OK，各馬達角度都在上限內。

**尚待**：APK 端 `DanceScriptPlayer`（照 `t` 排程送 `ctlMotor`）＋ `INTERNET` 權限與傳輸；`MAX_SPEED_DEG_PER_SEC=150` 需實機確認。

## 2026-09-24 PC Offload 第二步：APK 腳本播放 + Wi-Fi 接收

**完成狀態**：
- ✅ `robot/DanceScriptPlayer`（純 Java）：預備 1.5 秒 → 依絕對時間送出每一步 → 播完歸位；送出前再夾角度 / 速度，未知馬達拒送；停止用旗標 + 中斷 worker。
- ✅ `net/ScriptServer`（純 Java，java.net，無外部函式庫）：`GET /status`、`POST /script[?play=1]`、`/play`、`/stop`、`/home`，埠號 8765，腳本上限 8MB。
- ✅ `MainActivity`：新增「播放腳本」（讀 `dance_script.json`）與「開啟 / 關閉遠端接收」按鈕；Stop 也會停止腳本播放。版面改為 ScrollView（按鈕變多放不下），View id 不變。
- ✅ `RobotController.sendMotorCommand()`（不等待）；`lastCommandedDegree` 改 ConcurrentHashMap。`RobotMapper.maxDegFor()` 給播放端夾位用。
- ✅ Manifest 加 `INTERNET`。
- ✅ 電腦端：`pc/send_script.py`（標準函式庫）、`pc/java/.../FakeKebbi`（沒有機器人時驗證整條路）。
- ✅ 驗證：`FakeKebbi` 端到端 —— 時間軸對齊（t=600ms 的步在 1500+600ms 送出）、播放中停止、重複播放被拒、錯誤馬達拒送、超限角度 / 速度被夾、壞 JSON 回 400。`gradlew assembleDebug` BUILD SUCCESSFUL。

**尚待實機**：實際 ctlMotor 的延遲與動作觀感；Kebbi 的 Wi-Fi 能否被電腦連到（部分校園網路會隔離裝置）。
