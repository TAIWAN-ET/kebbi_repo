# 目前進度與待辦（2026-10-05）

> 分支：`pc-offload`。細節與數據見 [`pc/report/CHANGES.md`](pc/report/CHANGES.md)（依時間順序的修改紀錄）、
> [`DEVELOPMENT_LOG.md`](DEVELOPMENT_LOG.md)（開發日誌）、[`pc/README.md`](pc/README.md)（PC 端用法）。

## 一句話

影片 → 3D 姿態（rtmlib RTMW3D）→ App 內同一份 Java 映射成馬達腳本 → 傳給 Kebbi（或 FakeKebbi）播放；
並可在 PyBullet 模擬、網頁上對照影片與 3D 模型。**所有東西都已在電腦上驗證，還沒有在實機上跑過。**

## 管線

```
影片 ─► pc/extract_pose3d.py        rtmlib Wholebody3d（RTMW3D-x，有深度）
          ├─ pose_landmarks.csv         normalized 2D
          └─ pose_world_landmarks.csv   3D（單位＝影像高度，x 右 / y 下 / z 遠離鏡頭）
     ─► pc/make_dance.py --skip-extract  Java：PoseAnalyzer → RobotMapper → DanceScriptBuilder
     ─► dance_script.json
          ├─► pc/send_script.py → Kebbi / FakeKebbi（Wi-Fi 或 adb）
          ├─► pc/sim/pybullet_sim.py   PyBullet 模擬（可 --gui）
          └─► pc/report/               網頁：影片＋骨架＋3D 模型＋模擬結果
```

舊的 2D 來源（`extract_pose.py` MediaPipe、`rtmlib_to_landmarks.py` 轉 rtmlib 2D）仍可用；沒有深度時手臂自動退回 2D 規則。

## 已完成

| 項目 | 說明 | 驗證 |
|---|---|---|
| 專題包單位修正 | rtmlib 回傳像素，門檻卻是 0~1 normalized（33 幀中 26 幀被誤判傾斜）。`video_analyzer.py` 改 normalized，`auto_optimize.py` 擋掉像素 CSV | 33 幀：LEAN 26 → 1 |
| 3D 姿態來源 | `extract_pose3d.py`（RTMW3D-x，z 縮放 2.0、3 幀中值濾波） | 方向驗證：鼻子比肩膀更靠近鏡頭 |
| 手臂 3D 方向 | `PoseAnalyzer` 算上臂在身體座標系的 (前, 外, 上)，人轉身不影響；無深度時退回 2D | PyBullet 正向運動學對照 |
| 肩 X / Y / Z 映射 | 外側用肩 X+肩 Y（x=asin(外)、y=atan2(-前,-上)）；內收改用肩 Z；10 顆馬達全部接上 | FK 驗證方向 |
| 手肘 | 1:1 對應（原 0.6 倍），夾在 -80~0 | 誤差 35° → 24° |
| 貼近影片 | 依肢體各自判斷可信度（略過 54 → 4 幀）；指令提前到位（REACH_FRACTION 0.85） | 手臂方向誤差 36° → 26°（`eval_fidelity.py`） |
| 速度限制 | 官方 SDK：`ctlMotor` 速度 0~200 °/s，全部夾 200，頭上下 120 | 模擬峰值 ≈ 上限 |
| PyBullet 模擬 | `pc/sim/`：由 `robot.xml` 產 URDF、依關節分色、自碰撞檢測、`--gui` | 追蹤誤差 1~11°；新碰撞 2 組（頭×身體，≤ 6.9 mm） |
| 網頁對照 | `pc/report/`：影片＋偵測骨架並排 3D 模型（依關節分色）、可切換 PyBullet 實際角度、逐格 / 變速 | 已在瀏覽器確認 |
| 編譯 | `gradlew assembleDebug` 成功（`local.properties` 的 SDK 路徑已修正） | BUILD SUCCESSFUL |

## 尚待確認（需要實機或真值）

1. **實機安裝與播放**：目前沒有 adb 連線的裝置，APK 尚未裝到 Kebbi。
   先 Home 歸位 → 低速（約 100 °/s）試跑一小段 → 再跑完整腳本，人要在旁邊看著。
2. **肩 Z 方向**：「負值＝往胸前內收」是由官方動作範圍（-85~5，只有負值）與 PyBullet 推得，左右手正負號在實機上沒驗證。
3. **肩 X 方向**：左臂外展為正、右臂軸相反（右臂符號 -1）是實測 3D 模型與模擬得到的，要確認實機左右手都往外。
4. **速度上限 200 °/s**：只是 API 允許的上限，官方沒公布各馬達實際極限。上實機看有無異音、過熱、跟不上；必要時調低 `RobotMapper.SDK_MAX_SPEED_DEG_PER_SEC`。
5. **手臂 18° 外偏置**：模擬顯示機器人上臂本身有固定約 18° 外偏，確認實機是否同樣，是否需要補償。
6. **指令提前量**：`REACH_FRACTION` 0.85 是依模擬調的，實機馬達加速度（官方 1800 °/s²）與通訊延遲未納入，可能需重調。
7. **Wi-Fi 傳送**：「遠端接收」目前**沒有密碼**，同網段任何人都能讓機器人動；至少要加確認或密碼再正式使用。

## 尚待實現 / 已知限制

- **深度品質**：z 是模型估的，`zscale=2.0` 由模型單位換算、沒有真值校正；肩 Y 仍有少數大跳動。可考慮用多幀 / 卡爾曼平滑、或雙視角真值驗證。
- **取樣間隔 0.2 秒**：快動作在取樣之間已動完。可試 0.1 秒（CPU 約兩倍時間），或在腳本中補中間點。
- **頭部**：頭左右 / 上下仍用 2D 規則（鼻子相對肩中心），沒用 3D；轉頭幅度未校正。
- **只處理一個人**：多人影片取身體分數最高者；人轉身（背對鏡頭）時左右可能互換。
- **模擬是近似**：質量、力矩上限（5 N·m）是估計；碰撞用凸包近似；沒有馬達加速度限制；手掌 / 手指沒有。
- **評估指標**：誤差是相對「模型偵測到的姿勢」，不是真實姿勢；沒有人工標註的真值。
- **專題包**：`output/versions/*`、`optimization_history.json`、`robot_3d.html` 仍是像素資料算的舊成果，需 API key 重跑；
  `auto_optimize.py` 的 `hand_raise_threshold`/`ratio` 與 `lean_threshold`/`ratio` 各有一個參數被另一個蓋掉（`or` / `max` 寫法），尚未改。
- **網頁**：需連網載入 three.js（CDN）；長條圖數字是固定值。
- **Android 端**：App 內尚沒有「播放時顯示目前步驟 / 誤差」的介面；`RobotController`、Android 專屬流程只通過編譯，未實機跑。
- **未追蹤的檔案**：`AndroidManifest.xml`、`libs/`、`res/`、`aidl/`、`R.txt`、`classes.jar`、`NUWA_Robot_SDK_1.1.4.md` 一直是未追蹤狀態，
  `web_sim/index.html` 已刪除未提交——這次沒有動它們，需要時再決定要不要納入版控。

## 建議的下一步順序

1. 接上機器人（USB 或 Wi-Fi adb），`adb install -r app/build/outputs/apk/debug/app-debug.apk`。
2. 先用 FakeKebbi 確認腳本傳送流程，再上實機：Home → 低速試跑 → 檢查肩 Z / X 方向與速度 → 完整腳本。
3. 依實機結果調整：符號、`SDK_MAX_SPEED_DEG_PER_SEC`、`REACH_FRACTION`、18° 偏置。
4. 遠端接收加上保護，再考慮把 3D 資料用在頭部。
