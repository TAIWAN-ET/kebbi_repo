# FUNCTION_REFERENCE.md

> Kebbi 看影片學跳舞專題 — 函式參考手冊
> 最後更新：2026-08-04

---

## 資料層（Data Layer）

### Landmark
**位置**：`Landmark.java`

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
**位置**：`PoseFrame.java`

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
**位置**：`MotionSequence.java`

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
**位置**：`MotionSource.java`

**用途**：動作序列來源的 enum（VIDEO / CAMERA / JSON）。

**值**
| 值 | 說明 |
|---|---|
| VIDEO | 來自影片檔案 |
| CAMERA | 來自即時攝影機 |
| JSON | 來自 JSON 檔案 |

---

### PoseLandmark
**位置**：`PoseLandmark.java`

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
**位置**：`PoseFeature.java`

**用途**：人體動作特徵（角度、傾斜、手是否舉高等），是「人體資訊」不是「Robot 指令」。

**欄位**
| 欄位 | 類型 | 說明 |
|---|---|---|
| leftElbowAngle | float | 左肘角度（度） |
| rightElbowAngle | float | 右肘角度（度） |
| leftArmAngle | float | 左手臂角度（度） |
| rightArmAngle | float | 右手臂角度（度） |
| leftHipAngle | float | 左髋角度（度） |
| rightHipAngle | float | 右髋角度（度） |
| shoulderSlope | float | 肩膀傾斜角（度） |
| torsoLean | float | 軀幹傾斜角（度） |
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

## 數學工具層

### PoseMath
**位置**：`PoseMath.java`

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
**位置**：`PoseIO.java`

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
**位置**：`PoseAnalyzer.java`

**用途**：將 MotionSequence 轉換成 PoseFeature 序列。純 Java，不依賴 Android。

**方法**
| 方法 | 參數 | 回傳 | 說明 |
|---|---|---|---|
| `analyzeFrame(PoseFrame)` | 單一影格 | `PoseFeature` | 分析單幀的人體特徵 |
| `analyzeSequence(MotionSequence)` | 動作序列 | `List<PoseFeature>` | 分析整段序列 |
| `inferEventType(boolean, boolean, float)` | 左手舉、右手舉、身體傾斜 | `String` | 推導姿勢事件類型 |

---

### PosePipeline
**位置**：`PosePipeline.java`

**用途**：資料層協調者，提供 round-trip 驗驗證。純 Java，不依賴 Android。

**方法**
| 方法 | 參數 | 回傳 | 說明 |
|---|---|---|---|
| `verify(File csvFile, File jsonFile)` | CSV 檔案 + JSON 檔案 | `String` | 執行 round-trip 驗證 |

---

### MotionPoseMap
**位置**：`MotionPoseMap.java`

**用途**：機器人內建 motion 與姿勢事件類型的對應表。

**方法**
| 方法 | 參數 | 回傳 | 說明 |
|---|---|---|---|
| `buildEventToMotionMap(List<String>)` | motion 清單 | `Map<String,String>` | 建立事件→動作查表 |
| `eventForMotion(List<String>, String)` | motion 清單 + motion 名稱 | `String` | 查詢 motion 對應的事件 |

---

## UI 層

### MainActivity
**位置**：`MainActivity.java`

**用途**：主 Activity，提供所有 UI 功能。

**方法**
| 方法 | 說明 |
|---|---|
| `onCreate(Bundle)` | 初始化 SDK、PoseLandmarker、UI 按鈕 |
| `analyzeBuiltInClip()` | 分析內建影片（dance_input.mp4） |
| `analyzeDanceVideo(Uri)` | 分析使用者選擇的舞蹈影片 |
| `speak()` | 讓 Kebbi 說話 |
| `showMotionPicker()` | 彈出選單選擇並播放 motion |
| `playDanceMotion()` | 依舞蹈步驟播放動作 |
| `testBuiltInMotions()` | 測試內建 motion |
| `stopMotion()` | 停止動作和 TTS |

---

### CameraTestActivity
**位置**：`CameraTestActivity.java`

**用途**：Camera + MediaPipe 煙霧測試，不做任何姿態辨識或機器人控制。

**方法**
| 方法 | 說明 |
|---|---|
| `onCreate(Bundle)` | 初始化 PoseLandmarker、相機、權限 |
| `onRequestPermissionsResult(...)` | 處理相機權限結果 |

---

## Call Graph

```
MainActivity
│
├── analyzeBuiltInClip()
│      │
│      ▼
│   PoseAnalyzer.analyzeFrame(PoseFrame)
│      │
│      ▼
│   PoseMath.calculateAngle()
│   PoseMath.calculateDistance()
│   PoseMath.calculateMidPoint()
│   PoseMath.normalize()
│   PoseMath.lowPass()
│   PoseMath.isConfident()
│
├── analyzeDanceVideo(Uri)
│      │
│      ▼
│   PoseIO.writeCsv() → CSV 檔案
│   PoseIO.writeJson() → JSON 檔案
│   PosePipeline.verify(csvFile, jsonFile)
│      │
│      ▼
│   PoseIO.readCsv() → MotionSequence
│   PoseIO.writeJson()
│   PoseIO.readJson() → MotionSequence
│   PosePipeline.verifyEquality()
│
├── playDanceMotion()
│      │
│      ▼
│   MotionPoseMap.buildEventToMotionMap()
│   NuwaRobotManager.motionPlay()
│
├── showMotionPicker()
│      │
│      ▼
│   MotionPoseMap.eventForMotion()
│   NuwaRobotManager.motionPlay()
│
└── CameraTestActivity
       │
       ▼
    PoseLandmarker.detectForVideo()
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
MainActivity.toPoseFrame()
    │ 轉換為 PoseFrame (33 個 Landmark)
    ▼
PoseAnalyzer.analyzeFrame()
    │ 計算角度/距離/傾斜
    ▼
PoseFeature (人體動作特徵)
    │
    ├──→ PoseIO.writeCsv() / writeJson() (儲存)
    ├──→ PosePipeline.verify() (驗證)
    ├──→ MotionPoseMap (事件類型對照)
    └──→ RobotMapper (未來：轉為機器人指令)
```