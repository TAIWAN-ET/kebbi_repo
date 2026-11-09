# 動作庫 v1 與效率優先 v1.1（原第七、七-B、七-C 章）

> 兄弟檔：`docs\project\KEBBI_DANCE_PROJECT.md`、`PROJECT_RISKS_AND_LOG.md`。總索引見 `docs/README.md`。

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

## 七-B、抽帧動作庫 v1（P3.6，2026-10-20）

### 為什麼要做

原本「每秒一個 keyframe」只能描述「這一秒大概在做什麼」，複雜動作（例如手在 1 秒內轉一圈）仍需要大量逐幀指令。動作庫的目標是把複雜動作壓成**少量可參數化的動作原子**，機器人端只收到「圓：圓心、半徑、圈數、方向、時間」這樣的參數，不必逐幀計算。

### 原理（抽帧 / Nyquist / aliasing）

- 動作若要被正確重建，每個週期至少要有 `minSamplesPerCycle = 5` 點，理想 `8` 點。
- 1 秒 1 圈 ⇒ 建議抽幀 `1000 / 8 = 125ms`（約 8Hz），基準 `10Hz`。
- 若每週期點數不足，寧可輸出 `FREEFORM` 並標記 `UNDERSAMPLED`，也不硬拗成 `CIRCLE`（避免 aliasing 造成方向/圈數誤判）。
- 停滯 `>= 300ms` 切 `HOLD`；位移死區 `0.02` 肩寬；活動速度門檻 `0.12` 肩寬/秒。
- 切分採 MDL：`score = RMSE + 0.006 × tokenCost`，避免用大量 token 硬湊出語意錯誤的動作。

### 座標與方向

- 原點：兩肩中點；x 向右、y 向下；單位：肩寬。
- 畫面順時針記為 `CW`。

### 資料流

```
PoseFeature（含 leftWristX/Y、rightWristX/Y、headX/Y）
      ↓ MotionScriptFactory.fromFeatures()
MotionScriptBuilder.addTrack("LEFT_WRIST" / "RIGHT_WRIST" / "HEAD")
      ↓ Trajectory（重採樣、平滑）
MotionSegmenter（停滯 / 活動分段）
      ↓ PrimitiveClassifier（HOLD / CIRCLE / SWING / WAVE / RAISE / LOWER / TURN / NOD / SHAKE / LEAN）
MotionToken[] → MotionScript（Metrics：壓縮率 / RMSE / 保真度）
```

### 輸出檔案

| 檔案 | 內容 |
|---|---|
| `dance_motion_script.json` | 完整動作腳本（軌跡、token、metrics、warnings） |
| `dance_motion_script.txt` | 人類可讀摘要（`toCompact()`） |
| `motion_library.json` | 動作字典（每種原子動作的規則、參數、機器人動作） |
| `dance_keyframes.csv` | 既有秒級表（未變動） |

### v1 驗證數據（PC，`MotionSamplingVerify`）

| 案例 | 結果 |
|---|---|
| 1 秒 1 圈 @10Hz | 1 個 `CIRCLE`，r≈0.398、rev=1.00、CW、conf=0.98，semanticReduction=90.9%，commandReduction=70.0%，RMSE=0.038 |
| 同上 @5Hz | conf≈0.81，RMSE≈0.067（仍可接受） |
| 同上 @2Hz | `FREEFORM` + `UNDERSAMPLED`（正確拒絕 aliasing） |
| 畫圓 1s + 抬手 0.5s | MDL 切成 `CIRCLE + RAISE`，RMSE=0.026 |

驗證指令：

```powershell
javac -encoding UTF-8 -d "$env:TEMP\motion_out" C:\kebbi\app\src\main\java\com\example\myapplication\motion\*.java
java "-Dfile.encoding=UTF-8" -cp "$env:TEMP\motion_out" com.example.myapplication.motion.MotionSamplingVerify
```

### 目前邊界

- 動作腳本目前**只輸出、不接入播放**；`MainActivity` 既有 keyframe 播放行為未改動。
- `VideoAnalyzer` 仍以 `FRAME_INTERVAL_MS = 200L`（5Hz）抽幀；七-C 決策為 PC 10Hz、Android 5Hz，固定抽幀，程式待改。
- 真實影片回歸（2026-10-20）：`37421255165-1-192.mp4` 得到 16 tokens、RMSE 0.1794（舊制 pass=false）、左手 `CIRCLE` conf=0.56；七-C 已改為 semantic-first，並記錄待修項。
- RMSE 0.08 hard gate 已被七-C 的 semantic-first 政策取代；標準文件待同步。

### 標準文件

完整標準、公式、決策樹、JSON schema、驗收門檻與治理流程見：`C:\kebbi\docs\standards\MOTION_LIBRARY_STANDARD.md`。

---

## 七-C、效率優先 v1.1：臉部表情、底盤並行與模型選型（2026-10-20 研究，尚未實作）

> 本節是方向修正與開源模型調查紀錄；**程式尚未修改**。P3.6 動作庫 v1 保留，但驗收哲學由「重建誤差優先」改為「語意近似優先」。

### C-1 為什麼放棄高保真複雜動作

| 限制 | 影響 |
|---|---|
| 只有 10 顆上半身馬達（頸 2、肩 3×2、肘 1×2） | 無手腕旋轉、手指、腿；人類細節無法重現 |
| 機器人動作慢 | 高速/高頻動作即使 token 正確，播放也看不出來 |
| 硬碟 10GB、CPU 弱 | 不適合在機器人端跑重模型或逐幀優化 |
| 追求 RMSE < 0.08 | 會產生大量 token，違背「少量指令」目標 |

決策：

1. **固定抽幀**：PC 離線 10Hz；Android 即時 5Hz。不做自適應抽幀、不做 30fps 全帧、不做 3D pose lifting。
2. **語意優先**：動作類型、方向、粗略振幅正確即可；複雜或取樣不足的動作允許 `FREEFORM` 或映射到最接近的原子。
3. **驗收分流**：
   - `semanticPass`（必要）：primitive 正確、方向正確、無 aliasing 誤判、壓縮率達標。
   - `rmsePass`（參考）：RMSE ≤ 0.15 為 warning；不再單獨判 fail。
4. **MDL 成本可調**：`tokenCost` 0.006 可視情況提高到 0.008~0.010，用更少 token 換效率；每次調整都要跑 `MotionSamplingVerify` + 真實影片回歸。
5. **待修 bug**：`MotionScriptBuilder.mergeAdjacent()` 合併 CIRCLE 時信心取平均，導致 conf=0.56 < 0.60 仍輸出；v1.1 改取 max 或 weighted max。

非目標：

- 不追求 133 點 wholebody、不追求 3D 重建、不追求手指/手腕細節。
- 不把 AI 放進機器人即時回路。

### C-2 真實影片回歸現況（2026-10-20）

`video_push/37421255165-1-192.mp4`（10 秒）：

| 指標 | 數值 |
|---|---|
| 抽幀 | 51 幀 @5Hz |
| token | 16 |
| semanticReduction | 87.8% |
| commandReduction | 74.7% |
| RMSE | 0.1794（舊制 pass=false） |
| 左手 | `CIRCLE` r=0.042、rev=0.96、T=829ms、CW、conf=0.56 |

解讀：小半徑畫圓 + 5Hz 每圈點數不足 + 合併信心平均，造成舊制 fail。新制下語意（CIRCLE / CW）正確，應判 `semanticPass`，RMSE 只列 warning；但仍應把 PC 抽幀改 10Hz、修正合併信心。

### C-3 臉部辨識與表情顯示

需求拆分：

- **人臉偵測**：找到臉、框、追蹤 ID。
- **人臉辨識**：這是誰（需註冊資料庫）；與表情是不同模型。
- **表情辨識**：7 類離散情緒，或 52 維 blendshape。
- **顯示**：PC 預覽視窗或 Kebbi 平板 overlay。

| 功能 | 模型 | 平台 | 授權 | 重點 | 建議 |
|---|---|---|---|---|---|
| 臉偵測 | MediaPipe BlazeFace / Face Detector | Android/PC | Apache 2.0 | 輕量、與 `tasks-vision` 共用 | Android 首選 |
| 臉偵測 | YuNet | Android/PC（OpenCV） | MIT | WIDER AP 0.884 / 0.866 / 0.750；可偵測約 10×10~300×300 的臉 | PC / 離線首選 |
| 臉辨識 | SFace（MobileFaceNet） | Android/PC（OpenCV） | Apache 2.0 | LFW 0.994；輕量、可離線 | 產品首選 |
| 臉辨識 | ArcFace / InsightFace buffalo_l | PC | 程式 MIT；**預訓練模型非商業** | 精度高、生態完整 | 研究可用，商用需授權 |
| 表情（離散） | EmotiEffLib（MobileNet / EfficientNet-B0） | PC/Android（ONNX） | Apache 2.0 | 7/8 類；MobileNet 14MB / 16ms、EfficientNet-B0 16MB / 59ms（Snapdragon 888）；AffectNet 7 類 64.7~66.5% | 候選；v0 未採用 |
| 表情（離散） | FER+（ONNX Model Zoo） | PC | MIT | 7 類；約 3.8ms/次（CPU） | **v0 實作** |
| 表情（離散） | DeepFace | PC | MIT 包裝；**底層模型各自授權** | 7 類、快速原型 | 原型即可 |
| 表情（AU / 連續） | LibreFace | PC | **USC Research（非商業）** | AU + 表情、即時 | 學術可用，產品避免 |
| 表情（blendshape） | MediaPipe Face Landmarker | Android/PC | Apache 2.0 | 478 landmarks + 52 blendshapes | Android 顯示首選 |

建議堆疊：

- **PC 離線（v0 已實作）**：YuNet（偵測）→ SFace（辨識）→ FER+（7 類情緒）→ OpenCV 畫框/標籤；需要 mesh 時再加 MediaPipe Face Landmarker。EmotiEffLib 仍是候選。
- **Android 即時（第二階段）**：MediaPipe Face Landmarker（478 點 + 52 blendshapes，現有 `tasks-vision` 已包含）→ blendshape 啟發式映射到 7 類；若離散準確度不足，再加 EmotiEffLib MobileNet（14MB，約 16ms）。
- 不要在 Android 端引入 InsightFace 預訓練模型（授權）或 LibreFace（非商業）。

顯示契約（預定）：

```java
class FaceObservation {
    long timestampMs;
    int trackingId;
    RectF bbox;
    String name;            // "Unknown" 表示未註冊
    float identityScore;
    String emotion;         // Happy / Sad / Angry / Surprise / Fear / Disgust / Neutral
    float emotionScore;
    float[] blendshapes;    // 52 維，可選
}
```

Overlay 內容：臉框 + 名稱 + 情緒 + 信心；可選 mesh / blendshape 長條。PC 端輸出 annotated MP4 + JSON，透過 USB/adb 傳給 Kebbi；Android 優先播放 annotated MP4（框與表情已燒入），沒有影片才用 `FaceOverlayView` 畫 JSON。

隱私：

- 只在本機處理，不上傳雲端。
- 人臉辨識需被攝者同意；只存 embedding，不存原始臉部影像。
- 提供「不啟用辨識」模式，只顯示表情/臉框。

### C-4 底盤移動與並行馬達

Nuwa SDK 事實（`javap` 反組譯 `NuwaSDK-2021-07-08...aar`）：

| 項目 | 事實 |
|---|---|
| 馬達 | 只有 10 顆：`MOTOR_NECK_Y/Z`、左右肩 `Z/Y/X`、左右肘 `Y`；**沒有腿/腳馬達** |
| 底盤 | `move(float)`、`turn(float)`、`moveInSpeed(long,float,float,MOVE,boolean)`、`turnToDOA(float)`、`lockWheel()/unlockWheel()` |
| 馬達指令 | `ctlMotor(int,float,float)` 是非同步 `Handler.post`；`ctlMotor(int,int,float,float)` 會忽略第 2 個 int，勿用 |
| 並行 | Action framework 有 `SpawnAction`、`BodyAction`、`DriveAction`，可組合並行 |
| 現況 | `executeRobotCommands()` 每個指令 `sleep(duration+500ms)` → 序列；`home()` 一次 post 全部馬達 → 接近並行 |

決策：

- **「腳步」無法重現**：Kebbi 是輪式底盤，不是雙足；只能以 `move/turn` 近似位移/轉身，或用身體傾斜代替踏步。
- **新增 `MotionScheduler`（規劃）**：`RobotCommand` 增加 `startTimeMs`；以 100ms tick 分組，同一 tick 的馬達/底盤指令一起送出；總時間取 group 內最大 duration，而不是 sum。
- **並行安全**：底盤移動時手臂不要大幅外伸；限制速度；先在磚塊/支架上測；保留緊急停止。
- **驗收**：手臂 10 顆馬達可同時啟動；底盤 + 手臂開始時間誤差 ≤100ms；總時長接近腳本。

### C-5 計時器的定位

- `MainActivity` 的 `timerText` 只作為**驗收工具**：比對「實際播放時間 vs 腳本預期時間」、記錄分析耗時。
- 不是產品功能；不花時間做 UI 動畫/統計圖。未來可移到 debug build 或長按顯示，但本輪不改程式。

### C-6 開源模型選型（姿態/動作）

| 用途 | 模型 | 平台 | 授權 | 評價 |
|---|---|---|---|---|
| Android 姿態 | MediaPipe Pose Landmarker（33 點） | Android | Apache 2.0 | 維持現狀；與 Face Landmarker 同框架 |
| PC 姿態 | RTMPose-s/m/x | PC | Apache 2.0（MMPose） | 可選；s 已足夠，x 更準但慢 |
| PC 多人 | RTMO | PC | Apache 2.0 | 多人/遮擋時再用 |
| PC wholebody | RTMW | PC | Apache 2.0 | 133 點；機器人不需要，暫不採用 |
| PC 包裝 | YOLO11-pose | PC | **AGPL-3.0** | 除非專案開源，否則避免 |
| PC 研究 | ViTPose / Sapiens | PC | 研究/重模型 | 不必要 |

決策：**本階段不換姿態模型**。Android 維持 MediaPipe；PC 若需要離線高精度，先用 RTMPose-s 做對照，不急著上 x / wholebody。

> 實測基準（RTMPose lightweight vs balanced）與 VIDEOTEST 現況見 `docs/analysis/PC_ANALYSIS.md`。

### C-7 對 P3.6 標準的影響（待同步）

`docs\standards\MOTION_LIBRARY_STANDARD.md` 目前仍以 RMSE 0.08 為 hard gate、以 10Hz 為基準；v1.1 需同步：

1. `semanticPass` 為主、`rmsePass` 為 warning。
2. 固定 10Hz（PC）/ 5Hz（Android），移除自適應抽幀章節。
3. CIRCLE 合併信心改 max / weighted max。
4. `FREEFORM` 允許用於取樣不足或無法參數化的複雜動作。

> 本輪依指示只改三份主文件；標準文件留待下一輪同步，避免與程式行為不一致。

### C-8 下一步（依序）

| 階段 | 內容 | 驗收 |
|---|---|---|
| P3.7 | ✅ PC 臉部/表情離線管線（YuNet + SFace + FER+）+ USB/adb | `v0_face`；annotated MP4 + JSON；辨識/表情標籤正確 |
| P3.8 | Android 臉部顯示（PC JSON overlay 已實作；MediaPipe 原生分析規劃） | 螢幕顯示臉框 + 情緒；不影響現有播放 |
| P3.9 | MotionScheduler + 底盤並行 | 手臂同步、底盤 + 手臂同步、安全測試 |
| P3.10 | 動作標準 v1.1 + 真實影片回歸 | semanticPass、RMSE warning、CIRCLE 信心修正 |

---

