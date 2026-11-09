# 動作庫標準 — 腳本格式與壓縮驗收（§6~§7）

> 總則見 `docs\standards\MOTION_LIBRARY_STANDARD.md`。總索引見 `docs/README.md`。

## 6. 動作腳本格式

### 6.1 緊湊文字（`dance_motion_script.txt`）

```
KEBBI-MOTION/1
src=video dur=1500ms tokens=2 base=100ms idealSPC=8
RIGHT_WRIST|CIRCLE|0-1000|r=0.348|rev=1.00|T=1000|CW|conf=0.98|spc=11.0
RIGHT_WRIST|RAISE|1000-1500|pts=2|conf=1.00
# rawSamples=16 expansionPoints=11 naiveCommands=45 semanticReduction=87.5% commandReduction=75.6% rmse=0.0263 pass=true
```

一行一 token，機器人端可直接用 `|` 分割。
`UNDERSAMPLED` 會附加在該行末尾。

### 6.2 JSON（`dance_motion_script.json`）

```json
{
  "schema": "kebbi.motion-script/v1",
  "source": "video",
  "durationMs": 1500,
  "sampling": {
    "baseIntervalMs": 100,
    "minIntervalMs": 33,
    "maxIntervalMs": 333,
    "idealSamplesPerCycle": 8,
    "minSamplesPerCycle": 5,
    "minConfidence": 0.6,
    "maxReconstructionRmse": 0.08
  },
  "metrics": {
    "rawSamples": 16,
    "tokens": 2,
    "expansionPoints": 11,
    "baselineFps": 30.0,
    "naiveCommands": 45,
    "semanticReduction": 0.875,
    "commandReduction": 0.756,
    "rmse": 0.0263,
    "normalizedRmse": 0.023,
    "fidelity": 0.977,
    "pass": true
  },
  "tokens": [
    {
      "joint": "RIGHT_WRIST",
      "type": "CIRCLE",
      "startMs": 0,
      "endMs": 1000,
      "confidence": 0.98,
      "sampleCount": 11,
      "samplesPerCycle": 11.0,
      "samplingAdequate": true,
      "centerX": 0.0,
      "centerY": 0.0,
      "radius": 0.348,
      "startAngleRad": 0.0,
      "clockwise": true,
      "revolutions": 1.0,
      "periodMs": 1000.0
    },
    {
      "joint": "RIGHT_WRIST",
      "type": "RAISE",
      "startMs": 1000,
      "endMs": 1500,
      "confidence": 1.0,
      "sampleCount": 6,
      "samplesPerCycle": 6.0,
      "samplingAdequate": true,
      "periodMs": 0.0,
      "keyTimesMs": [1000, 1500],
      "keyXs": [0.348, 0.348],
      "keyYs": [0.0, -0.55]
    }
  ],
  "warnings": []
}
```

### 6.3 單位與版本規則

- 長度：肩寬倍數。
- 角度：度（token 參數若用弧度會標明 `Rad`）。
- 時間：毫秒。
- `schema` 欄位是唯一版本判斷依據；破壞性變更才升 major。

---

## 7. 壓縮與驗收標準

### 7.1 指標定義

| 指標 | 定義 | 說明 |
|---|---|---|
| `semanticReduction` | `1 - tokens / rawSamples` | 語意壓縮率 |
| `commandReduction` | `1 - expansionPoints / naiveCommands` | 指令壓縮率；naive = 30fps × 秒數 |
| `rmse` | 重建點與原始點的均方根誤差（肩寬） | 絕對誤差 |
| `normalizedRmse` | `rmse / 平均動作尺度` | 相對誤差 |
| `fidelity` | `1 - normalizedRmse` | 保真度 |

### 7.2 驗收門檻（Level A~D）

| Level | 項目 | 門檻 |
|---|---|---|
| A | 取樣 | CIRCLE 的 SPC >= 5；低於則 `UNDERSAMPLED` |
| B | 語意 | confidence >= 0.60；否則 FREEFORM |
| C | 重建 | `rmse <= 0.08` 肩寬；`pass = true` |
| D | 壓縮 | 10 秒舞蹈 `commandReduction >= 50%`；單圈 CIRCLE 為 1 token |

### 7.3 v1 實測（合成資料）

| 案例 | 抽幀 | token | 語意壓縮 | 指令壓縮 | RMSE | 結果 |
|---|---|---|---|---|---|---|
| 1 秒 1 圈 | 10Hz | 1 CIRCLE | 90.9% | 70.0% | 0.038 | PASS |
| 1 秒 1 圈 | 5Hz | 1 CIRCLE | 83.3% | 70.0% | 0.067 | PASS |
| 1 秒 1 圈 CCW | 5Hz | 1 CIRCLE | 83.3% | 70.0% | 0.066 | PASS |
| 2 秒 2 圈 | 10Hz | 1 CIRCLE | 95.2% | 71.7% | 0.018 | PASS |
| 1 秒 1 圈 | 2Hz | FREEFORM | 66.7% | 90.0% | — | 正確拒絕 |
| 1 秒擺盪 | 10Hz | 1 SWING | 90.9% | 70.0% | 0.036 | PASS |
| 抬手 0.6s + 停 1s | 5Hz | RAISE + HOLD | 77.8% | 91.7% | 0.000 | PASS |
| 畫圓 1s + 抬手 0.5s | 10Hz | CIRCLE + RAISE | 87.5% | 75.6% | 0.026 | PASS |
| 靜止抖動 | 10Hz | HOLD | 95.2% | 96.7% | 0.007 | PASS |

> 重跑方式見 `MOTION_STANDARD_GOVERNANCE.md` §10。所有測試必須全過，才可修改門檻或分類規則。

---

