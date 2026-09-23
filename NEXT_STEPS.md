# 接上 Kebbi 之後的驗證步驟

> 建立日期：2026-09-06
> 前置：已完成 Batch 1 + 2 的程式修正（詳見 `KEBBI_DANCE_PROJECT.md` 的 2026-09-06 紀錄）
> 目的：把「只能在 PC 上驗證的東西」拿到實機上量出真實數字，並回填程式常數

---

## 0. 現在的狀態

### 已經修好而且驗證過的

| 項目 | 驗證方式 |
|---|---|
| `ctlMotor` 第三參數改為速度（度/秒） | 官方 JavaDoc + 反組譯 AAR（`setSpeedInDegreePerSec`）雙重確認 |
| 手臂角改為髖-肩-肘，不再和手肘角同算式 | PC 端實測：高舉時 arm=168°、elbow=120°，確實分離 |
| 角度改用 world landmark（公制） | 編譯通過，`getAngleLandmarks()` 有 CSV 回退路徑 |
| 休息姿勢映射為全 0 度 | PC 端實測：六顆馬達全部 0.00 |
| 六顆馬達同時動、只等一次 | 程式碼路徑確認 |
| 第二次分析影片不再崩潰 | 每次分析建立各自的 Landmarker |

`gradlew assembleDebug` → **BUILD SUCCESSFUL**，APK 已產生。

### 還沒驗證、需要實機的

1. **馬達軸向**：`MOTOR_NECK_Y` / `MOTOR_NECK_Z` 哪個是左右轉，官方文件沒寫
2. **馬達真實極限**：舊的 yaw 90° / pitch 30° 是在速度單位錯誤下量的，不可信
3. **scale 倍率**：目前是「人體可動範圍 ÷ 機器人可動範圍」推的，機器人那一半還是猜的
4. **event → motion 對應**：現在是亂配的（見 S6）

**這份文件的重點就是 1 和 2。** 這兩個沒量出來，3 和 4 沒辦法定案。

---

## 1. 前置作業

### 1-1　環境確認

```bash
/c/Users/phage/AppData/Local/Android/Sdk/platform-tools/adb.exe devices
```

確認機器人的 Android 版本（`minSdk` 是 24，低於 24 裝不起來）：

```bash
/c/Users/phage/AppData/Local/Android/Sdk/platform-tools/adb.exe shell getprop ro.build.version.sdk
```

> ⚠️ `local.properties` 裡的 `sdk.dir` 指向不存在的 `C:\AndroidSDK`。
> 用 Android Studio 開會自動修正；用命令列就得帶 `ANDROID_HOME`（見下）。

### 1-2　建置

```bash
cd "C:/Users/phage/Desktop/kebbi-20260817T072839Z-1-001/kebbi" && ANDROID_HOME="C:\Users\phage\AppData\Local\Android\Sdk" ./gradlew.bat assembleDebug
```

### 1-3　安裝

```bash
/c/Users/phage/AppData/Local/Android/Sdk/platform-tools/adb.exe install -r "C:/Users/phage/Desktop/kebbi-20260817T072839Z-1-001/kebbi/app/build/outputs/apk/debug/app-debug.apk"
```

### 1-4　推測試影片

```bash
/c/Users/phage/AppData/Local/Android/Sdk/platform-tools/adb.exe push "C:/Users/phage/Desktop/kebbi-20260817T072839Z-1-001/kebbi/video_push/37421255165-1-192.mp4" /sdcard/Android/data/com.example.myapplication/files/dance_input.mp4
```

> **檔名必須是 `dance_input.mp4`。**
> UI 上那顆「分析影片(前10秒)」按鈕呼叫的是 `analyzeBuiltInClip`，讀固定檔名。
> `setupVideoPicker()` 雖然註冊了檔案選擇器，但程式裡沒有任何地方呼叫 `.launch()`，
> 所以選擇器目前進不去（見「已知限制」）。

### 1-5　開 logcat（另開一個視窗掛著）

```bash
/c/Users/phage/AppData/Local/Android/Sdk/platform-tools/adb.exe logcat -c && /c/Users/phage/AppData/Local/Android/Sdk/platform-tools/adb.exe logcat -s PoseDebug:I CameraTest:I AndroidRuntime:E
```

---

## 2. 驗證主線

> 順序不能跳。S1 沒做完，S2 量到的數字沒有意義；S2 沒做完，S3 沒得填。

### S0　確認 SDK 連通

**操作**：開 App，**等 2～3 秒**，按 **Home (歸位)**。

**為什麼要等**：`RobotController` 在 `onCreate` 就初始化 SDK，但官方文件明講
「不要在 `onWikiServiceStart` 之前呼叫任何 API」。太快按會拿到
`Nuwa robot SDK is not ready`。

**通過條件**：機器人有反應，狀態列不是 `not ready`。

- [ ] S0 通過

---

### S1　確認馬達軸向 ★ 阻塞後續全部步驟

**目的**：官方 JavaDoc 對 `MOTOR_NECK_Y` / `MOTOR_NECK_Z` 只寫 "Motor neck y" / "Motor neck z"，
沒說哪個是 yaw（左右轉）、哪個是 pitch（上下點）。
機器人學慣例是 Z 軸垂直向上、繞 Z 轉才是左右轉，**所以目前的對應有可能是反的**。

**先做安全防護**：這個測試會掃到 yaw 90° / pitch 30°，而這兩個數字並不可信。
第一次跑建議先把範圍縮小 —— 編輯
`app/src/main/java/com/example/myapplication/robot/RobotController.java`：

```java
// 第 45~46 行，第一次測試時暫時改小
private static final float HEAD_YAW_MAX_DEG = 40f;    // 原本 90f
private static final float HEAD_PITCH_MAX_DEG = 15f;  // 原本 30f
```

改完重新建置安裝（1-2、1-3）。

**操作**：按 **Test Head Limits**。程式會先掃 `NECK_Y`（目前當成 yaw），再掃 `NECK_Z`（當成 pitch）。

**要盯著看的**：第一段掃描時，Kebbi 的頭是**左右轉**還是**上下點**？

| 觀察結果 | 動作 |
|---|---|
| 左右轉 | 對應正確，什麼都不用改 |
| 上下點 | **對應反了** → 改 `RobotMotor.java` 第 66 行和第 71 行 |

反了的話這樣改
（`app/src/main/java/com/example/myapplication/robot/RobotMotor.java`）：

```java
public static final int NECK_YAW = NECK_Z;     // 第 66 行，原本 NECK_Y
public static final int NECK_PITCH = NECK_Y;   // 第 71 行，原本 NECK_Z
```

**只要改這兩行**，`RobotMapper` 和其他程式碼一行都不用動 —— 這是當初刻意留的語意別名。

- [ ] S1 完成，軸向為：☐ 目前對應正確　☐ 已對調

**緊急停止**（聽到異音或卡住時）：

```bash
/c/Users/phage/AppData/Local/Android/Sdk/platform-tools/adb.exe shell am force-stop com.example.myapplication
```

> 注意：`Stop Motion` 按鈕**停不了**極限測試（`sweepMotor` 不檢查停止旗標），
> 要停只能用上面這行或直接關 App。

---

### S2　重測馬達極限

**目的**：拿到真實可動範圍。舊的 90° / 30° 是在「速度 1.5 度/秒 + 只等 2 秒」的條件下量的，
每一步都還沒走完就被判定「卡住」，數字整個是錯的。
現在等待時間會依步進角度計算（5° ÷ 45°/s ≈ 0.11s + 0.4s 安定），量到的才是真的。

**操作**：把 S1 改小的範圍改回去（或改成你認為安全的上限），重新建置安裝，然後：

1. 按 **Test Head Limits** → 記錄 yaw / pitch
2. 按 **Test Hand Limits** → 記錄肩膀 / 手肘

**畫面會列出**每一步的 `target=  actual=  err=`。

**判讀方式**：
- `err` 一直很小（< 2°）→ 還在可動範圍內
- `err` 開始暴增，或 `actual` 不再變化 → **到極限了，記下這個角度**
- 出現 `>> 偵測到卡住` → 連續 3 步沒動，確定到底了

**記錄表**：

| 馬達 | 常數名 | 舊值（不可信） | 實測極限 |
|---|---|---|---|
| 頭部左右轉 | `NECK_YAW_MAX_DEG` | 90 | ________ |
| 頭部上下點 | `NECK_PITCH_MAX_DEG` | 30 | ________ |
| 肩膀 | `SHOULDER_MAX_DEG` | 60 | ________ |
| 手肘 | `ELBOW_MAX_DEG` | 60 | ________ |

- [ ] S2 完成，四個數字都量到了

---

### S3　回填常數

**操作**：把 S2 量到的數字填進
`app/src/main/java/com/example/myapplication/robot/RobotMapper.java` 第 65~68 行：

```java
private static final float NECK_YAW_MAX_DEG = 90f;    // ← 換成實測值
private static final float NECK_PITCH_MAX_DEG = 30f;  // ← 換成實測值
private static final float SHOULDER_MAX_DEG = 60f;    // ← 換成實測值
private static final float ELBOW_MAX_DEG = 60f;       // ← 換成實測值
```

**順便檢查倍率**（第 51~57 行）。倍率的取法是「機器人可動範圍 ÷ 人體可動範圍」：

| 常數 | 目前值 | 怎麼算 |
|---|---|---|
| `ARM_SCALE` | 0.45 | 肩膀極限 ÷ 150（人體手臂 20°→170°） |
| `ELBOW_SCALE` | 0.45 | 手肘極限 ÷ 135（人體手肘 175°→40°） |
| `HEAD_YAW_SCALE` | 1.35 | 影片裡頭轉幅度通常小，放大才看得出來 |
| `HEAD_PITCH_SCALE` | 1.4 | 同上 |

例如實測肩膀極限是 45°，那 `ARM_SCALE` 應該改成 `45 / 150 = 0.30`。

- [ ] S3 完成，已重新建置安裝

---

### S4　驗收映射層

**操作**：按 **Test RobotMapper**（還沒分析影片時會播 4 組樣本姿勢）。

**預期行為**：

| 樣本 | 預期動作 |
|---|---|
| `neutral` | **六顆馬達全部 0°，機器人站直不動** |
| `both_hands_up` | 雙肩抬起 + 雙肘彎曲 |
| `head_right_rh_up` | 頭右轉 + 右手平舉 |
| `pitch_down_lh_up` | 低頭 + 左手平舉 |

**第一個 `neutral` 是關鍵驗收點。** 舊版這裡會把手肘彎成 60°
（人體休息值 175° × 0.5 = 87.5 → clamp 60）。
PC 端已驗證修正後映射為全 0，實機應該真的站直不動。

**同時要感受的**：動作是「六個關節一起動」還是「一個一個動」？
舊版是後者（一個姿勢要 9 秒），現在應該是前者。

- [ ] S4 通過，neutral 確實站直
- [ ] S4 通過，關節同時動而非逐一動

---

### S5　影片分析 → 播放

**操作**：
1. 按 **分析影片(前10秒)**，等分析完成
2. **再按一次分析影片** ← 這是在驗證修好的崩潰 bug

**舊版第二次一定會噴「分析失敗」**（VIDEO 模式共用 Landmarker，時間戳從 10000 倒退回 0，
MediaPipe 直接拋例外，必須重啟 App）。現在應該可以連續分析。

3. 按 **Play Dance**

**要看的 log**（`PoseDebug` tag）：每幀會印出
`L-arm= L-elbow= headYaw= headPitch= ... confident=`。

**重點檢查**：`L-arm` 和 `L-elbow` 的數值**必須不一樣**。
如果一樣，代表哪裡出錯了（舊版這兩個永遠相等）。

- [ ] S5 第二次分析沒有崩潰
- [ ] S5 log 裡 L-arm ≠ L-elbow

**拉回分析結果**：

```bash
/c/Users/phage/AppData/Local/Android/Sdk/platform-tools/adb.exe pull /sdcard/Android/data/com.example.myapplication/files/ "C:/Users/phage/Desktop/kebbi_output"
```

會拿到 `dance_pose_landmarks.csv` / `.json`、`dance_keyframes.csv`、`dance_upperbody_log.txt`。

> `dance_keyframes.csv` 的 `robot_motion` 欄位現在是空的，這是**正常的**。
> 舊版那欄只是把 `event_type` 複製一份，沒有任何作用。

---

### S6　建立 motion 白名單

**問題**：`Play Dance` 走的是「keyframe → 內建 motion」這條路，
而 `MotionPoseMap` 是用 `"left"` / `"right"` / `"both"` / `"up"` 去比對 motion 名稱。
你機器上的 motion 叫 `888_ML_Petdonkey_24`、`001_K11_TutorialV3085` 這種，
**一個關鍵字都命中不了**，全部掉進 fallback 按清單順序亂配。

所以你會看到「舉左手 → 播騎驢動作」。**這個沒有程式解，只能人工建表。**

**操作**：
1. App 裡會顯示完整 motion 清單，抄下來
2. 逐支播放，人眼判斷「這支像舉左手 / 像舉右手 / 像雙手舉高 / 像左傾 / 像右傾」
3. 把結果寫死進
   `app/src/main/java/com/example/myapplication/robot/MotionPoseMap.java`

建議改成一張明確的對照表取代關鍵字猜測：

```java
private static final Map<String, String> MANUAL_MAP = new LinkedHashMap<>();
static {
    MANUAL_MAP.put("LEFT_HAND_UP",  "888_ML_Haveidea_20");   // ← 填實測結果
    MANUAL_MAP.put("RIGHT_HAND_UP", "888_ML_HugFox_22");     // ← 填實測結果
    MANUAL_MAP.put("BOTH_HANDS_UP", "888_ML_Petdonkey_24");  // ← 填實測結果
    MANUAL_MAP.put("LEAN_LEFT",     "...");
    MANUAL_MAP.put("LEAN_RIGHT",    "...");
}
```

**記錄表**：

| Event | 對應的 motion 名稱 |
|---|---|
| `LEFT_HAND_UP` | ________________ |
| `RIGHT_HAND_UP` | ________________ |
| `BOTH_HANDS_UP` | ________________ |
| `LEAN_LEFT` | ________________ |
| `LEAN_RIGHT` | ________________ |

- [ ] S6 完成

---

## 3. 調參對照表

實機跑起來之後，如果覺得哪裡不對，查這張表：

| 症狀 | 調哪個 | 檔案 : 行 |
|---|---|---|
| 動作太快 / 太急 | `DEFAULT_SPEED_DEG_PER_SEC` 調小 | `RobotMapper.java:83` |
| 動作太慢 / 跟不上 | `DEFAULT_SPEED_DEG_PER_SEC` 調大 | `RobotMapper.java:83` |
| 動作抖動、抽搐 | `SMOOTHING_ALPHA` 調小（更平滑） | `VideoAnalyzer.java:104` |
| 動作太鈍、跟不上快舞步 | `SMOOTHING_ALPHA` 調大 | `VideoAnalyzer.java:104` |
| 幅度太小、看不出在跳舞 | 對應的 `_SCALE` 調大 | `RobotMapper.java:51~57` |
| 幅度太大、一直打到 clamp | 對應的 `_SCALE` 調小 | `RobotMapper.java:51~57` |
| 站著不動時馬達卻有角度 | `ARM_REST_DEG` / `ELBOW_REST_DEG` | `RobotMapper.java:41,43` |
| 頭一直低著或一直抬著 | `HEAD_PITCH_REST_DEG` | `PoseAnalyzer.java:50` |
| 「舉手」判定太寬鬆 / 太嚴格 | `ARM_RAISED_DEG` | `PoseAnalyzer.java:53` |
| 太多幀被當成遮擋跳過 | `CONFIDENCE_THRESHOLD` 調小 | `PoseAnalyzer.java:40` |
| 極限測試步進太粗 | `HEAD_STEP_DEG` | `RobotController.java:44` |
| 極限測試每步等太久 | `MOTOR_SETTLE_MS` | `RobotController.java:60` |
| 單一姿勢等太久 | `MAX_POSE_WAIT_MS` | `RobotController.java:62` |
| 只想分析更長的片段 | `CLIP_ANALYZE_MS` | `VideoAnalyzer.java:96` |
| 取樣太疏 / 太密 | `FRAME_INTERVAL_MS` | `VideoAnalyzer.java:93` |

---

## 4. 疑難排解

| 症狀 | 原因 / 處理 |
|---|---|
| `Nuwa robot SDK is not ready` | 開 App 後太快按按鈕，等 2～3 秒再按 |
| `INSTALL_FAILED_OLDER_SDK` | 機器人 Android 版本 < API 24，要降 `app/build.gradle.kts` 的 `minSdk` |
| `SDK location not found` | `local.properties` 路徑錯，建置時帶 `ANDROID_HOME`（見 1-2） |
| 找不到 `dance_input.mp4` | 檔名或路徑不對，重跑 1-4 |
| 頭部掃描到一半沒反應 | 真實極限比設定值小，這是**正常的**，看誤差記錄即可 |
| 極限測試停不下來 | 用 `am force-stop`（見 S1），`Stop Motion` 按鈕對它無效 |
| 分析第二支影片失敗 | 如果還是失敗，把 logcat 的 `AndroidRuntime:E` 內容貼出來 |

---

## 5. 已知限制（目前刻意沒動的）

這些是現況，不是 bug，但你應該知道：

1. **檔案選擇器進不去** —— `setupVideoPicker()` 有註冊 `ActivityResultLauncher`，
   但沒有任何按鈕呼叫 `.launch()`。`analyzeDanceVideo()` 目前是從 UI 到不了的死路。
   要換影片只能覆蓋 `dance_input.mp4`。
   （要修的話：加一顆按鈕呼叫那個 launcher。）

2. **CameraTestActivity 沒有入口** —— `AndroidManifest` 裡是 `exported="false"`，
   `MainActivity` 也沒有啟動它的程式碼。即時相機模式目前只能用 adb 手動起：
   ```
   adb shell am start -n com.example.myapplication/.camera.CameraTestActivity
   ```
   （它已經改成官方建議的 LIVE_STREAM + `detectAsync`，但還沒實機跑過。）

3. **只分析前 10 秒** —— `CLIP_ANALYZE_MS = 10000L`。

4. **`Play Dance` 走的是內建 motion，不是 ctlMotor** ——
   架構文件主打的「PoseFeature → RobotCommand → ctlMotor」那條路，
   目前只有 `Test RobotMapper` 按鈕會走。兩條路還沒合併（見下）。

---

## 6. 做完這些之後的下一個決策點

S1～S6 跑完，你會面對一個架構選擇：

| 路線 | 優點 | 缺點 |
|---|---|---|
| **A. Keyframe + 內建 motion**（現在 `Play Dance` 走的） | 動作流暢好看，內建 motion 是專業做的 | 只是「每秒挑一支罐頭動畫」，很難說是「學會跳舞」 |
| **B. PoseFeature → ctlMotor 直接驅動**（現在 `Test RobotMapper` 走的） | 真的在模仿影片裡的人，符合專題主張 | 動作可能生硬，要調參 |
| **C. 混合** | 上半身用 ctlMotor 連續模仿，偵測不到人時用內建 motion 當 filler | 要多寫一層切換邏輯 |

**建議 C**，但先跑完 S1～S5 看 B 的實際效果再決定 —— 修正前的 B 因為速度單位和角度算式都錯，
根本沒被公平評估過。

---

## 附錄：常用指令

```bash
# 建置
cd "C:/Users/phage/Desktop/kebbi-20260817T072839Z-1-001/kebbi" && ANDROID_HOME="C:\Users\phage\AppData\Local\Android\Sdk" ./gradlew.bat assembleDebug
```

```bash
# 安裝
/c/Users/phage/AppData/Local/Android/Sdk/platform-tools/adb.exe install -r "C:/Users/phage/Desktop/kebbi-20260817T072839Z-1-001/kebbi/app/build/outputs/apk/debug/app-debug.apk"
```

```bash
# 啟動
/c/Users/phage/AppData/Local/Android/Sdk/platform-tools/adb.exe shell monkey -p com.example.myapplication -c android.intent.category.LAUNCHER 1
```

```bash
# 看 log
/c/Users/phage/AppData/Local/Android/Sdk/platform-tools/adb.exe logcat -s PoseDebug:I CameraTest:I AndroidRuntime:E
```

```bash
# 強制停止
/c/Users/phage/AppData/Local/Android/Sdk/platform-tools/adb.exe shell am force-stop com.example.myapplication
```

```bash
# 拉回分析結果
/c/Users/phage/AppData/Local/Android/Sdk/platform-tools/adb.exe pull /sdcard/Android/data/com.example.myapplication/files/ "C:/Users/phage/Desktop/kebbi_output"
```

---

## 回報格式

S2 量完之後，把這段貼給我，我幫你算 scale 並改程式：

```
軸向：目前對應正確 / 已對調
NECK_YAW_MAX_DEG   = ___
NECK_PITCH_MAX_DEG = ___
SHOULDER_MAX_DEG   = ___
ELBOW_MAX_DEG      = ___
neutral 樣本：有站直 / 沒站直
關節動作：同時動 / 逐一動
```

---

## S7（新）PC Offload 腳本播放

> 2026-09-24 新增。電腦端產生 `dance_script.json`，Kebbi 照腳本播放。詳見 `pc/README.md`。

1. 電腦：`pc/.venv/Scripts/python pc/make_dance.py video_push/<影片>.mp4 --max-seconds 20`
2. `adb push pc/out/<影片>/dance_script.json /sdcard/Android/data/com.example.myapplication/files/dance_script.json`
3. Kebbi 按「播放腳本」，觀察：動作是否和影片同步、有沒有馬達異音（→ 調低 `DanceScriptBuilder.MAX_SPEED_DEG_PER_SEC`）
4. 試 Wi-Fi：按「開啟遠端接收」→ 電腦 `python pc/send_script.py <IP> <json> --play`
