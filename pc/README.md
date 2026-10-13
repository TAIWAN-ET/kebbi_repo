# PC 端分析工具（PC Offload）

在電腦上把舞蹈影片轉成 `dance_script.json`，再交給 Kebbi 播放。
Kebbi 上不用再跑 MediaPipe。

```
影片 ─► extract_pose.py (Python + MediaPipe)
          ├─ pose_landmarks.csv         normalized 座標
          └─ pose_world_landmarks.csv   world 公制座標
     ─► extract_face.py (MediaPipe Face Landmarker，用姿態框出頭部)
          └─ face_blendshapes.csv       52 個臉部 blendshape 分數
     ─► DanceScriptGenerator (Java，直接重用 App 的程式碼)
          PoseIO → 平滑 → PoseAnalyzer → RobotMapper → DanceScriptBuilder   → steps（馬達）
          FaceIO → FaceExpressionAnalyzer → FaceScriptBuilder → KebbiFaceMap → faces（換臉）
     ─► dance_script.json   ← 送給 Kebbi 的檔案
```

## 第一次安裝

需要 Python 3.12（MediaPipe 目前還不支援 3.13）和 JDK 17 以上。
`make_dance.py` 會依序到 `JAVA_HOME`、`~/.gradle/jdks`、Android Studio 內建 JBR 去找 JDK，通常不用另外設定。

```bash
cd pc
py -3.12 -m venv .venv
.venv/Scripts/python -m pip install -r requirements.txt
```

## 網頁操作介面（建議）

不想記指令的話，用網頁介面就能完成全部流程：選／上傳影片 → 分析 → 預覽（影片和馬達角度、換臉同步顯示）→ 輸入 Kebbi IP → 上傳並播放。

```bash
cd pc
.venv/Scripts/python -m pip install -r webui/requirements.txt   # 只多裝 Flask
.venv/Scripts/python webui/app.py                               # 自動開啟 http://127.0.0.1:8800
```

- 影片放在 `video_push/`，或直接在網頁上傳（存到 `pc/uploads/`）
- 網頁只是包裝上面這些指令（`make_dance.py`、`extract_pose3d.py`、`sim/pybullet_sim.py`、`send_script.py`），執行紀錄會即時顯示；參數意義與命令列相同
- 預覽影片要瀏覽器能播的格式（mp4 H.264 / webm）
- 「上傳並播放」時可同時播放影片，延後時間預設 1500 ms（播放器就位時間），之後依延遲量測結果調整
- 沒有機器人：展開「用本機 FakeKebbi 測試」，啟動後 IP 填 `127.0.0.1`
- 「校正與同步工具」：延遲量測（`measure_latency.py`）、手臂軸向檢查（`check_arm_axes.py`）、影片同步播放（`sync_play.py`）
  目前還沒放進 repo，檔案放進 `pc/` 就會自動啟用；參數對應在 `webui/app.py` 的 `EXTRA_TOOLS`

## 產生腳本

```bash
.venv/Scripts/python make_dance.py ../video_push/37421255165-1-192.mp4 --max-seconds 20
```

輸出在 `pc/out/<影片檔名>/`。常用參數：

| 參數 | 說明 |
|---|---|
| `--max-seconds N` | 只分析前 N 秒 |
| `--interval 200` | 取樣間隔（毫秒），預設和 App 相同 |
| `--name xxx` | 腳本內的舞蹈名稱 |
| `--skip-extract` | 沿用上次的 CSV，只重跑映射（調 `RobotMapper` 參數時用，幾秒就好）；沒有臉部 CSV 時仍會跑臉部辨識 |
| `--no-face` | 不做臉部表情辨識，腳本裡沒有換臉事件 |

## 調參數

映射參數寫在 **App 的原始碼**，這裡是直接拿來編譯的，所以改一份兩邊都生效：

- 倍率、休息角度、馬達上限：`app/.../robot/RobotMapper.java`
- 死區、速度上下限：`app/.../robot/DanceScriptBuilder.java`

改完直接跑 `make_dance.py ... --skip-extract` 就好，不用重裝 APK。

## 臉部表情

`extract_face.py` 每個取樣點量一次臉（和姿態同一組 `time_ms`），第一次執行會自動下載
`face_landmarker.task`（約 3.7 MB）到 `pc/models/`。舞蹈影片的臉通常很小，所以先用姿態的臉部點框出頭部、
放大後再偵測，框不到才用整張畫面。

判斷規則在 `app/.../analysis/FaceExpressionAnalyzer.java`，表情只分 5 種：
NEUTRAL、HAPPY、SURPRISED、ANGRY、SAD。用 1163 張有標註的網路人臉照片量過：整體 47%（隨機猜 20%），
HAPPY / SURPRISED 的精確度 0.6~0.7，ANGRY / SAD 約 0.4，拿不準時判成 NEUTRAL（不換臉）。

換臉時間（`app/.../robot/FaceScriptBuilder.java`、參數在 `KebbiFaceMap.java`）：

| 規則 | 參數 | 原因 |
|---|---|---|
| 新表情要連續出現才算 | `MIN_STABLE_MS` 600 | 過濾眨眼、說話嘴形、偵測雜訊 |
| 換臉後至少停留 | `MIN_HOLD_MS` 1200 | Kebbi 的臉是一段動畫，換太快只會一直閃 |
| 換臉時間＝表情**開始**出現的那一幀 | | 離線分析知道未來，不用等確認穩定才換 |
| 再提早送出 | `FACE_LEAD_MS` 200 | 機器人端的臉部服務 + 動畫過渡延遲（腳本整份先傳到機器人，Wi-Fi 延遲不影響對齊） |
| 看不到臉太久回預設臉 | `FACE_LOST_RESET_MS` 3000 | 轉身、遮擋時不要一直卡在某個表情 |

表情 → Kebbi 臉的名稱也在 `KebbiFaceMap.java`。**名稱還沒在實機上確認**（SDK 文件沒有臉部動畫清單），
改完重跑 `make_dance.py ... --skip-extract` 就好，不用重裝 APK。

量準確度 / 調門檻：照片依表情放在子資料夾（`happy/`、`surprised/`、`angry/`、`sad/`、`neutral/`），

```bash
.venv/Scripts/python eval_face.py <照片資料夾> --out out/face_eval.csv
java -cp build/classes com.example.myapplication.pc.FaceExpressionEval out/face_eval.csv
```

## 腳本格式

見 `app/.../io/DanceScriptIO.java` 的類別註解。每一步只列出「有變化的馬達」，
速度設成「在下一個取樣點之前剛好轉到」，播放端照 `t` 送出指令即可。

## 注意

- `stubs/` 裡是 `MainActivity` 和 `CameraTestActivity` 的空殼。App 的純 Java 類別為了 JavaDoc 連結
  import 了這兩個 Android 類別，PC 上要有空殼才編得過。只用在 PC 編譯，不會進 APK。
- 要讓 PC 端多用一個 App 類別，就把它加到 `make_dance.py` 的 `SHARED_SOURCES`。那個類別不能 import `android.*`。

## 傳給 Kebbi 播放

APK 上有兩個新按鈕：**播放腳本**、**開啟遠端接收**。

**方法 A：USB（adb push）**

```bash
adb push out/<影片檔名>/dance_script.json /sdcard/Android/data/com.example.myapplication/files/dance_script.json
```

然後在 Kebbi 上按「播放腳本」。每次按都會重新讀檔，換新腳本不用重開 App。

**方法 B：Wi-Fi（不用插線）**

1. Kebbi 上按「開啟遠端接收」，畫面會顯示它的 IP
2. 電腦（同一個 Wi-Fi）：

```bash
python send_script.py <Kebbi IP> out/<影片檔名>/dance_script.json --play
python send_script.py <Kebbi IP> --stop      # 停止
python send_script.py <Kebbi IP> --home      # 歸位
python send_script.py <Kebbi IP> --status    # 狀態
```

收到的腳本會存成 `dance_script.json`，之後按「播放腳本」也能重播。
遠端接收**沒有密碼**，同一個區網的人都能讓機器人動，用完請關掉。

**沒有機器人時測試**：`FakeKebbi` 跑的是和 App 完全相同的接收器與播放器，只是把馬達指令印出來：

```bash
.venv/Scripts/python make_dance.py <影片> --skip-extract    # 會順便編譯 FakeKebbi
java -cp build/classes com.example.myapplication.pc.FakeKebbi
python send_script.py 127.0.0.1 out/<影片檔名>/dance_script.json --play
```

## 播放器行為（`DanceScriptPlayer`）

- 先送第一步，等 1.5 秒讓機器人就位，才開始計時
- 每一步都對齊絕對時間送出，不會越播越慢；落後時立刻補送
- 送出前再夾一次角度（`RobotMapper.maxDegFor`）與速度（≤150°/s），RobotMapper 不會驅動的馬達一律拒送
- 換臉事件和馬達步驟用同一個時間軸（同一時間先換臉）；第一次換臉時把 Kebbi 的臉叫到最上層，結束時收起來
- 正常播完自動歸位；按 Stop 則停在原地
