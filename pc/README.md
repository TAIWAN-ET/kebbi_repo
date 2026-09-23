# PC 端分析工具（PC Offload）

在電腦上把舞蹈影片轉成 `dance_script.json`，再交給 Kebbi 播放。
Kebbi 上不用再跑 MediaPipe。

```
影片 ─► extract_pose.py (Python + MediaPipe)
          ├─ pose_landmarks.csv         normalized 座標
          └─ pose_world_landmarks.csv   world 公制座標
     ─► DanceScriptGenerator (Java，直接重用 App 的程式碼)
          PoseIO → 平滑 → PoseAnalyzer → RobotMapper → DanceScriptBuilder
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
| `--skip-extract` | 沿用上次的 CSV，只重跑映射（調 `RobotMapper` 參數時用，幾秒就好） |

## 調參數

映射參數寫在 **App 的原始碼**，這裡是直接拿來編譯的，所以改一份兩邊都生效：

- 倍率、休息角度、馬達上限：`app/.../robot/RobotMapper.java`
- 死區、速度上下限：`app/.../robot/DanceScriptBuilder.java`

改完直接跑 `make_dance.py ... --skip-extract` 就好，不用重裝 APK。

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
- 正常播完自動歸位；按 Stop 則停在原地
