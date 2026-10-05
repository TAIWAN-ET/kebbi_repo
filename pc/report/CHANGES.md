# rtmlib 單位修正與 FakeKebbi 展示 — 修改說明

> **最新狀態與待辦請看 [`../../PROGRESS.md`](../../PROGRESS.md)。** 本檔是依時間順序的修改紀錄，前面章節的部分敘述（例如「Java 沒有改動」、速度數值）已被後面的章節取代，以最後面的為準。


日期：2026-09-29

## 問題
專題包的 `video_analyzer.py` 用 rtmlib 偵測，rtmlib 回傳**像素座標**（鼻子約 x=667、y=203），
但 `classify_event` / `auto_optimize.py` 的門檻（0.05、0.03）是 Kebbi `PoseAnalyzer` 的 **0~1 normalized** 單位。
結果門檻變成 0.05 像素：出貨的 33 幀中 26 幀被判為 LEAN。AI 自動調參也是在錯誤單位下調的，
所以 README 建議回寫 Kebbi 的 0.07 / 0.018 不可信。

## 修改內容

### 專題包（`D:\下載\專題打包_20260928`）
| 檔案 | 修改 |
|---|---|
| `VIDEOTEST/video_analyzer.py` | 偵測後 x/影像寬、y/影像高；`rtmlib.csv` 末尾新增 `image_w`、`image_h`；AI 提示的座標/肩寬改為 normalized（小數 3 位） |
| `VIDEOTEST/auto_optimize.py` | 讀到像素版 CSV 直接報錯；摘要小數位數放寬到 3 位 |
| `VIDEOTEST/output/rtmlib.csv` | 換成 normalized 版（原檔備份 `rtmlib_pixel_backup.csv`） |
| `README.md` | 新增第 0 節：修正說明、過期成果、更正「間隔約 2 秒」 |

### Kebbi 專案（`kebbi/`）
| 檔案 | 說明 |
|---|---|
| `pc/rtmlib_to_landmarks.py`（新增） | rtmlib.csv → MediaPipe 33 點 CSV（`pose_landmarks.csv` + 等比例座標 `pose_world_landmarks.csv`），接到 `make_dance.py --skip-extract` |
| `pc/report/`（新增） | 本說明、網頁展示（`index.html`、`kebbi.glb`、`build_report.py`、`serve.py`） |
| `../.claude/launch.json` | 新增 `kebbi-report`（埠 8131） |

Kebbi 的 Java / App 程式碼**沒有改動**。

## 驗證結果
- 33 幀出貨資料：修正前 26 幀 LEAN → 修正後 1 幀 LEAN、26 幀 NEUTRAL。
- 修正後 `video_analyzer.py` 實跑整支影片（0.2 秒間隔，383 幀）：座標範圍 x 0.139~0.746、y 0.031~0.971。
- 產生腳本：291 步、1339 條馬達指令、63.9 秒、JSON round-trip OK。
- FakeKebbi（埠 18765）：291/291 步、0 條被拒、最大延遲 0 ms；所有馬達在官方範圍內。

## 重現
```bash
# 1) 轉換 + 產生腳本
python pc/rtmlib_to_landmarks.py <VIDEOTEST>/output/rtmlib.csv --out-dir pc/out/rtmlib_demo
pc/.venv/Scripts/python pc/make_dance.py video_push/37421255165-1-192.mp4 --skip-extract --out-dir pc/out/rtmlib_demo --name rtmlib_demo --interval 167
# 2) FakeKebbi
java -cp pc/build/classes com.example.myapplication.pc.FakeKebbi 18765
python pc/send_script.py 127.0.0.1 --port 18765 pc/out/rtmlib_demo/dance_script.json --play
# 3) 網頁
python pc/report/build_report.py
python pc/report/serve.py 8131 kebbi   # 開 http://localhost:8131/pc/report/index.html
```

## 已知限制
- 88/383 幀（23%）被略過：必要關鍵點信心 < 0.5（左手腕 66 幀）。rtmlib 分數尺度與 MediaPipe 不同，0.5 門檻可能偏嚴。
- rtmlib Body 為 2D，無深度；手臂朝鏡頭時角度偏差。
- `versions/*`、`optimization_history.json`、`robot_3d.html` 是像素資料算的，已過期，需 API key 重跑。
- `hand_raise_threshold` 會被 `hand_raise_ratio` 蓋掉、`lean_ratio` 會被 `lean_threshold` 蓋掉（各有一個參數無作用），尚未修改。
- 網頁的機器人是官方 3D 模型（`kebbi.glb`，複製自 `kebbi_3d_model/model/`），由腳本馬達角度直接轉動對應關節（頭 2、肩 Y 2、肘 2）；需連網載入 three.js（CDN）。尚未套用限位/碰撞模擬，可用滑鼠旋轉視角。
- 網頁的修正前後長條圖數字是固定值（來自前面的驗證），不會隨資料重算。

## 追加：手臂 X 軸與 PyBullet 模擬（2026-09-29）

### 手臂軸
- `RobotMapper`：新增左右肩 X（側向外展，範圍 -3~100）。規則：手臂抬起量 × `SHOULDER_X_FLARE_RATIO`（0.35）。
  這是估計值：2D 影像看不出手臂往前或往側抬，所以抬升量仍主要給肩 Y，肩 X 只做「隨抬高而張開」。設 0 可關閉。
- `DanceScriptPlayer`：肩 X 加入播完歸位的馬達清單。腳本現在每步最多 8 顆馬達（共 1665 條指令）。
- **肩 Z 沒有接到 mapper**：肩 Z（前後擺動）在 2D 影像沒有可觀測的來源，腳本不會驅動它。網頁與模擬都已支援肩 Z，之後有資料即可直接用。
- 網頁 3D 模型：10 顆馬達全接上。實測右臂的肩 X、肩 Z 軸與左臂相反（同樣正值左臂外展、右臂內收），網頁與模擬都已用符號 -1 修正。
- 注意：Java 有改動，**還沒在 Android Studio 編譯，也沒上實機**（PC 端 javac 編譯與 FakeKebbi 流程正常）。

### PyBullet 模擬（`pc/sim/`）
| 檔案 | 說明 |
|---|---|
| `build_urdf.py` | 由 `kebbi_3d_model/robot.xml` + 網格產生 URDF（10 顆 revolute，其餘 fixed，官方角度範圍） |
| `pybullet_sim.py` | 播放腳本（位置控制 + 腳本速度上限），記錄實際角度、追蹤誤差、超速指令、新出現的自碰撞；`--gui` 可開視窗 |
| `sim_result.json` | 上述結果（網頁讀取） |

結果（rtmlib_demo，63.9 秒）：平均追蹤誤差 0~11°（肩 Y / 肘最大，主因是目標跳很快時馬達速度追不上）；
9 條頭上下指令超過 120°/s 的速度上限；新碰撞 2 組（`footprint2_link × head3_link` 最深 6.9 mm，163 幀）。

限制：質量與力矩上限（5 N·m）是估計值；碰撞用凸包近似；動力學不開自碰撞（整個身體的凸包會把手臂包住而使模擬炸掉），
碰撞另外用距離查詢檢測，只作參考。網頁的「模型改用 PyBullet 實際角度」開關可對照腳本與模擬。

環境：`pip install pybullet`（Python 3.13 需從原始碼編譯，需 Visual Studio C++ 建置工具，約 15 分鐘；3.12 以下可能有現成 wheel）。
執行：`python pc/sim/pybullet_sim.py pc/out/rtmlib_demo/dance_script.json` → `python pc/report/build_report.py`。

## 追加：改用有深度的姿態模型（rtmlib Wholebody3d / RTMW3D）

- `pc/extract_pose3d.py`（新增）：影片 → RTMW3D-x（3D）→ `pose_landmarks.csv` + `pose_world_landmarks.csv`，可直接接 `make_dance.py --skip-extract`。
  模型第一次執行會自動下載（偵測 yolox_m 約 90 MB、RTMW3D-x 約 350 MB）。CPU 跑 64 秒影片（每 0.2 秒一幀，320 幀）約數分鐘。
- 座標：x/y 用 keypoints_2d（影像像素，等比例），z 換算成同單位（`--zscale`，預設 2.0，由模型單位換算；自動估計實測很平坦，不可靠）。
  方向已驗證：鼻子比肩膀更靠近鏡頭，符合「x 右、y 下、z 遠離鏡頭」。3D 座標做 5 幀時間中值濾波（`--median`），
  肩 Y 超過 60° 的跳動從 25 次降到 7 次；代價是快動作會被抹平。
- `PoseFeature` / `PoseAnalyzer`：新增 `hasArmDirection` 與左右上臂方向（前/外/上，身體座標系，人轉身也不影響）。沒有深度的來源（z 全 0）自動退回舊的 2D 規則。
- `RobotMapper.addArm3d`：由 3D 方向反解肩 X = asin(外)、肩 Y = atan2(-前, -上)，已用 PyBullet 正向運動學驗證（除機器人上臂本身固定 18° 外偏外，方向吻合）。
- **肩 Z 仍不驅動**：肩 X + 肩 Y 已能表達任意手臂方向，肩 Z 與它們冗餘（同一個方向有多種組合），且左右手正負號慣例尚未實機確認。
- 結果（`pc/out/rtmw3d`，64.0 秒）：259 步、1479 條指令、略過 54/320 幀（17%）。肩 Y 現在涵蓋 -200°~+56°（含手臂往後）。
  PyBullet：肩 Y 平均誤差左 13°、右 7°（雜訊與快速大幅動作造成），其餘 1~4°；新碰撞 2 組（頭與身體，最深 6.9 mm）。
- 限制：深度是模型估的，非量測；雜訊仍在（肩 Y 還有 5 次超過 120° 的跳動）；`zscale` 沒有真值校正。
- 網頁的影片/模型/曲線已改用 `rtmw3d` 的腳本與模擬結果（`build_report.py`）。

## 追加：肩 Z（水平轉動 / 內收）已接上

- 原因：肩 X 只能外展（-3~100），手臂往胸前內收做不到；官方動作的肩 Z 範圍 -85~5（只有負值＝內收）。
- `RobotMapper.addArm3d`：手臂朝外或垂直（外 ≥ 0）用肩 X + 肩 Y（Z = 0）；朝內（外 < 0）改用肩 Z 把往前舉的手臂水平轉向胸前：
  h = √(前²+外²)、y = atan2(-h, -上)、z = atan2(外, 前)，兩個解在外 = 0 時相等，切換連續。已用 PyBullet FK 驗證方向。
- `DanceScriptPlayer`：肩 Z 加入播完歸位的馬達清單。
- 結果（`pc/out/rtmw3d`）：1538 條指令；左右肩 Z 各約 30 條、範圍 -85°~0°。PyBullet 肩 Z 平均誤差 3~4°，其餘與前一版相近。
- 限制：只在有深度（3D）時啟用；2D 來源仍不驅動肩 Z。Java 仍未在 Android Studio 編譯、未上實機。

## 追加：手臂動作貼近影片（量化調整）

新增 `pc/sim/eval_fidelity.py`：把偵測到的手臂方向（3D 與畫面投影）與 PyBullet 實際手臂方向逐幀比較（信心夠的 266 幀）。

| 版本 | 手臂 3D 方向誤差（平均，左/右） | 畫面投影誤差（平均，左/右） | 手肘彎曲誤差（平均，左/右） | 被略過幀 |
|---|---|---|---|---|
| 調整前 | 36 / 33° | 33 / 29° | 35 / 37° | 54 / 320 |
| 調整後 | 26 / 24° | 26 / 24° | 24 / 24° | 4 / 320 |

改了什麼（Java）：
- **依肢體判斷可信度**：`PoseFeature.headConfident / leftArmConfident / rightArmConfident`；`DanceScriptBuilder` 只略過看不清楚的肢體，其他照常跟隨（略過幀 54 → 4）。
- **提前發出指令**：每步時間 = 取樣時間 − 到位時間（`REACH_FRACTION = 0.85` × 取樣間隔），機器人在影片出現該姿勢那一刻到位。這是最大的改善（單項讓方向誤差約 36° → 23°，用未濾波資料）。
- **手肘 1:1 對應**（`ELBOW_SCALE` 0.6 → 1.0，超過機器人 80° 行程的部分夾住）。
- **各馬達速度上限改用官方 velocityLimit**（頭上下 120、頭左右 300、肩 Y 360、其餘 216°/s），取代全域 150°/s；播放器同步使用。**這會讓實機動得比以前快，實機上請先觀察有無異音／跟不上。**
- 3D 座標中值濾波預設改為 3 幀（試過 1/3/5；濾波越強誤差越大，但 1 幀時抖動較多，取 3 折衷）。

限制：
- 誤差是相對於「模型偵測到的姿勢」，不是相對於真實姿勢；偵測本身有雜訊，尤其是深度。
- 剩下的誤差主要來自 0.2 秒取樣（快動作在取樣之間已經動完）、馬達速度限制與機器人可動範圍（肘只有 80°、肩 X 不能內收）。
- Java 仍未在 Android Studio 編譯、未上實機。

## 追加：馬達速度依官方 SDK 限制

- 依據：官方 NUWA Robot SDK 1.1.4（專案內 `NUWA_Robot_SDK_1.1.4.md`）與[線上 NuwaUnity 文件](https://developer-docs.nuwarobotics.com/sdk/NuwaSDK_unity_readme.html)：
  `ctlMotor(..., setSpeedInDegreePerSec)` 範圍 **0 ~ 200 度/秒**。官方沒有公布各馬達更細的實際極限（網路上也查不到伺服馬達的速度規格，只有「12 顆伺服馬達」這類概述）。
- 之前用的模擬器 `hardware.xml` velocityLimit（頭左右 300、肩 Y 360）超過 API 上限，已不採用。
- 現在：`RobotMapper.maxSpeedFor` — 全部 200；頭上下維持 hardware.xml 的 120（較保守）。`DanceScriptBuilder` 與 `DanceScriptPlayer` 共用；PyBullet 模擬（`build_urdf.py`）同步。
- 驗證：模擬中實際速度峰值頭上下 121、其餘約 202 度/秒（多出的是取樣計算誤差）。
- 代價：快動作追不上，手臂方向誤差平均約 +2°（左/右 3D 方向 28 / 26°，畫面投影 28 / 25°）。
- 注意：200 只是 API 接受的上限，不保證實機馬達能達到或長時間承受；上實機請從較低速度開始測，若異音或過熱再調低 `SDK_MAX_SPEED_DEG_PER_SEC`。
