# Kebbi 看影片學跳舞專題 — 架構與進度總覽

> 文件性質：把舊有三份操作/日誌 txt 與目前程式碼架構整合為單一總覽。
> 最後更新：2026-07-15
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
| 33 點 → Kebbi 關節映射 | ❌ | 下一階段（但依本文件建議，先不做） |
| 動作評分/相似度 | ❌ | 研究加分 |

實測 5 支內建 motion（logcat 確認）：
`000_P4_MoveS500`、`888_ML_Petdonkey_24`、`888_ML_Haveidea_20`、`888_ML_HugFox_22`、`001_K11_TutorialV3085`

---

## 三、目前程式碼架構（Data Layer 已成型）

舊版問題：`MainActivity` 同時負責 UI、CSV、JSON、Round-trip，難維護。
現已抽出獨立 Data Layer，分層如下：

```
MainActivity (Android UI，幾乎只剩 UI)
   │ 只呼叫
   ▼
PosePipeline          ← 協調 + 強化驗證（verify）
   │
   ▼
PoseIO                ← CSV / JSON 讀寫（純 Java facade）
   │
   ▼
Landmark / PoseFrame / MotionSequence / MotionSource / PoseLandmark  ← 純 Java POJO
```

`MainActivity` 現在只做：
- `PosePipeline.verify(csvFile, jsonFile)` 取得 round-trip 結果字串並顯示。
- 原本的 Speak / Pick Video / Play Motion / Test Motions / Stop 按鈕邏輯（不動機器人控制）。

### 核心 POJO（100% 純 Java，零 Android import，PC / 單元測試可重用）

| 類別 | 職責 |
|---|---|
| `Landmark` | 單一關節點：`x, y, z, visibility, presence`（mutable public field，便於 JSON 函式庫反序列化） |
| `PoseFrame` | 一幀姿態：`frameIndex, timestampMs, List<Landmark>`，提供 `getLandmark(int id)` |
| `MotionSequence` | 整段舞蹈：`source(MotionSource), fps, List<PoseFrame>`，提供 `getFrameCount() / getLandmarkCount() / describe()` |
| `MotionSource` | enum：`VIDEO / CAMERA / JSON`（取代易拼錯的 String） |
| `PoseLandmark` | MediaPipe 33 點索引常數（如 `NOSE=0, LEFT_SHOULDER=11, RIGHT_WRIST=16...`），消滅魔術數字 |

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

### 檔案地圖
```
C:\kebbi\app\src\main\java\com\example\myapplication\
├── MainActivity.java        # Android UI + Nuwa SDK + MediaPipe 呼叫（不碰資料序列化）
├── Landmark.java            # POJO
├── PoseFrame.java           # POJO
├── MotionSequence.java      # POJO
├── MotionSource.java        # enum
├── PoseLandmark.java        # 33 點索引常數
├── PoseIO.java              # CSV/JSON I/O
└── PosePipeline.java        # round-trip 驗證
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
   RobotMapper           （PoseFeature → RobotCommand，絕不認識 MediaPipe）
      │
      ▼
   RobotCommand
      │
      ▼
    Kebbi
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
| P3 | RobotMapper（PoseFeature → RobotCommand） | ⏳ |
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
| MediaPipe 為 2D Landmark | 尚未處理人體遮擋（Occlusion）與深度誤差 | 後續利用 `visibility` / `presence` 提高穩定性（評審常問） |

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
