# 開發紀錄

> 專案：Kebbi 看影片學跳舞專題
> 專案位置：`C:\kebbi`
> 更新時間：2026-08-04

---

## 2026-08-04 註解加註工作

### 目標
依照規範逐一為 Java 檔案加註解：
1. 每個 class 前加入 JavaDoc（用途、誰會呼叫、不負責什麼）
2. 每個 public method 前加入 JavaDoc（功能、參數、回傳值、什麼時候呼叫）
3. 每個複雜邏輯區塊加入 `// Step1 // Step2` 註解
4. 不刪除任何原本程式
5. 不重構
6. 每完成一個檔案就停止，等待確認

### 完成狀態

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
| CameraTestActivity.java | ✅ 完成（類別 JavaDoc） |

### Git 安全點
- Commit: `5da1a00` - "safety point: before PoseMath verification work"
- Commit: `599f38d` - "feat: add PoseAnalyzer print button and documentation"
- Commit: `93f0d0c` - "feat: analyzeBuiltInClip writes CSV for PoseAnalyzer results"
- Commit: `a0e6b9a` - "fix: initialize poseAnalyzeButton with findViewById"

### Bug 修復
- `poseAnalyzeButton` 未被 `findViewById` 初始化，導致 App 啟動時 crash
- 已修正：添加 `poseAnalyzeButton = findViewById(R.id.poseAnalyzeButton);`