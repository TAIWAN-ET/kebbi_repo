package com.example.myapplication.robot;

import com.example.myapplication.model.PoseFeature;
import com.example.myapplication.model.RobotCommand;

import java.util.ArrayList;
import java.util.List;

/**
 * 人體動作特徵 → 機器人馬達指令的映射器。
 *
 * <p>用途：把 {@link PoseFeature}（人體資訊）轉成 {@link RobotCommand} 列表（機器人指令），
 * 絕不認識 MediaPipe / Landmark，只讀 PoseFeature 欄位。
 *
 * <p>誰會呼叫它：
 * {@link com.example.myapplication.MainActivity} 把分析結果的 PoseFeature 轉成指令後，
 * 交給 {@link RobotController} 執行。
 *
 * <p>不負責什麼：
 * 不連接馬達（由 {@link RobotController} 負責），
 * 不做資料序列化，不認識任何 SDK。
 * 也不判斷這一幀該不該用 —— 呼叫端要自己看 {@link PoseFeature#confident}
 * 決定要不要跳過遮擋幀，這裡永遠忠實映射。
 *
 * <p><b>核心觀念：休息姿勢偏移</b><br>
 * 人體角度和機器人角度的「零點」不一樣。人站直、手自然下垂時，
 * 手肘角度約 175 度、手臂抬升角約 20 度 —— 但這個姿勢對應的機器人角度應該是 0。
 * 舊版直接把人體角度乘上倍率送出去（{@code elbowAngle * 0.5}），
 * 結果「站著不動」被映射成「手肘彎 60 度」，
 * {@link #buildSamples()} 裡那個全 0 的 neutral 樣本真實資料永遠產生不出來。
 * 現在一律先扣掉休息值，再乘倍率。
 */
public class RobotMapper {

    // ---------------------------------------------------------------
    // 人體「休息姿勢」基準值：站直、雙手自然下垂時的角度
    // 這些值決定了「不動 = 機器人 0 度」，是整個映射的零點
    // ---------------------------------------------------------------

    /** 手臂抬升角（髖-肩-肘）在自然下垂時的角度。 */
    private static final float ARM_REST_DEG = 20f;
    /** 手肘角（肩-肘-腕）在手臂打直時的角度。 */
    private static final float ELBOW_REST_DEG = 175f;

    // ---------------------------------------------------------------
    // 各特徵轉馬達角度的倍率
    // 取法：機器人可動範圍 / 人體可動範圍
    // ---------------------------------------------------------------

    /** 頭部左右轉倍率。 */
    private static final float HEAD_YAW_SCALE = 1.35f;
    /** 頭部上下點倍率。 */
    private static final float HEAD_PITCH_SCALE = 1.4f;
    /** 手臂抬升倍率：人體 20~170 度（約 150 度行程）對應機器人上舉 0~-150 度（可舉到 -200）。 */
    private static final float ARM_SCALE = 1.0f;
    /**
     * 手肘彎曲倍率。1:1 對應，超過機器人行程（80 度）的部分夾住；
     * 舊值 0.6 會讓 45 度的小彎只剩 27 度，跟影片對不起來。
     */
    private static final float ELBOW_SCALE = 1.0f;
    /**
     * 手臂抬升量（已扣休息角）到達「水平側舉」的值；此前手臂只用肩 X 往側邊抬。
     *
     * <p>影片是正面拍的，手臂抬起在畫面上看到的是側向。肩 X 是側向外展（-3~100，90 度＝水平），
     * 所以水平以下純用肩 X。再往上，肩 X 的範圍不夠舉過頭，改成逐步把動作轉給肩 Y（往前上舉），
     * 到 {@link #ARM_OVERHEAD_DEG} 時完全是肩 Y -180（垂直向上）。
     */
    private static final float ARM_HORIZONTAL_DEG = 90f;
    /** 手臂抬升量（已扣休息角）到達「舉過頭」的值。 */
    private static final float ARM_OVERHEAD_DEG = 150f;
    /** 舉過頭時的肩 Y 角度。 */
    private static final float SHOULDER_Y_OVERHEAD_DEG = -180f;

    // ---------------------------------------------------------------
    // 各馬達容許的角度範圍（度）
    // 來源：NuwaUnity SDK「Motor angle range table」，與 NUWA 模擬器 hardware.xml 的
    // 編碼器範圍一致（NECK_Z 文件寫 ±40，編碼器只有 ±31，取 ±31）。
    // 範圍不對稱：抬手、彎肘都是負值（官方動作 666_RE_HiL：左肩 -131、左肘 -62）。
    // ---------------------------------------------------------------

    private static final float NECK_YAW_MIN_DEG = -31f, NECK_YAW_MAX_DEG = 31f;       // NECK_Z
    private static final float NECK_PITCH_MIN_DEG = -20f, NECK_PITCH_MAX_DEG = 20f;   // NECK_Y
    private static final float SHOULDER_MIN_DEG = -200f, SHOULDER_MAX_DEG = 70f;
    /** 肩 X（側向外展）：-3 ~ 100（兩手相同，正值＝往外）。 */
    private static final float SHOULDER_X_MIN_DEG = -3f, SHOULDER_X_MAX_DEG = 100f;
    /** 肩 Z（水平轉動）：-85 ~ 5，負值＝手臂往胸前內收（官方動作也只用負值）。 */
    private static final float SHOULDER_Z_MIN_DEG = -85f, SHOULDER_Z_MAX_DEG = 5f;
    private static final float ELBOW_MIN_DEG = -80f, ELBOW_MAX_DEG = 0f;

    /**
     * 每個指令的預設馬達速度（度/秒）。
     *
     * <p><b>單位是速度不是時間</b>：Nuwa 官方 API 是
     * {@code ctlMotor(int motor, float degree, float speed)}，
     * SDK 內部呼叫的是 {@code setSpeedInDegreePerSec}。
     * 舊版把這個欄位命名為 duration 並傳 1.0f，
     * 等於命令馬達以每秒 1 度爬行 —— 轉 60 度要 60 秒，
     * 但播放迴圈只等 1.5 秒就跳下一個指令，馬達永遠到不了目標角度。
     *
     * <p>60 度/秒是「一個 200ms 取樣間隔內大約能轉 12 度」的量級，
     * 適合逐幀模仿。要更柔和就調小，要更俐落就調大。
     */
    private static final float DEFAULT_SPEED_DEG_PER_SEC = 60f;

    /**
     * 將單一人體特徵映射為一組馬達指令。
     *
     * <p>回傳的 8 個指令是「同一個姿勢的 8 個關節」，
     * 應該由 {@link RobotController#executeRobotCommands} 一次全部送出讓馬達同時動，
     * 而不是一個一個等。
     *
     * @param feature PoseAnalyzer 產出的人體特徵
     * @return 對應的 RobotCommand 列表（頭 yaw / 頭 pitch / 左右肩 Y / 左右肩 X / 左右肘）
     */
    public List<RobotCommand> map(PoseFeature feature) {
        List<RobotCommand> commands = new ArrayList<>();

        // Step1：頭部轉動（headYaw / headPitch 的零點本來就是「正面平視」，不用扣休息值）
        // headYaw 正＝往畫面右轉＝人往自己左邊轉 → NECK_Z 正（機器人也往自己左邊轉，和手臂一樣不鏡像）
        commands.add(command(
                RobotMotor.NECK_YAW,
                feature.headYaw * HEAD_YAW_SCALE));
        // headPitch 正＝抬頭；NECK_Y 正＝低頭，所以反號
        commands.add(command(
                RobotMotor.NECK_PITCH,
                -feature.headPitch * HEAD_PITCH_SCALE));

        // Step2：肩膀（手臂抬升）— 扣掉自然下垂的休息角，再拆成肩 X（側舉）與肩 Y（往前上舉）
        // 水平以下：只有肩 X（側舉）；水平到舉過頭：肩 X 逐步收回、肩 Y 逐步到 -180
        if (feature.hasArmDirection) {
            // 有深度：用上臂的 3D 方向，前後、側向都是真的資料
            addArm3d(commands, RobotMotor.LEFT_SHOULDER_Z, RobotMotor.LEFT_SHOULDER_X, RobotMotor.LEFT_SHOULDER_Y,
                    feature.leftArmForward, feature.leftArmOut, feature.leftArmUp);
            addArm3d(commands, RobotMotor.RIGHT_SHOULDER_Z, RobotMotor.RIGHT_SHOULDER_X, RobotMotor.RIGHT_SHOULDER_Y,
                    feature.rightArmForward, feature.rightArmOut, feature.rightArmUp);
        } else {
            addArm(commands, RobotMotor.LEFT_SHOULDER_X, RobotMotor.LEFT_SHOULDER_Y, feature.leftArmAngle);
            addArm(commands, RobotMotor.RIGHT_SHOULDER_X, RobotMotor.RIGHT_SHOULDER_Y, feature.rightArmAngle);
        }

        // Step3：手肘（彎曲程度）— 手臂打直是休息值；機器人彎肘是負值
        commands.add(command(
                RobotMotor.LEFT_ELBOW_Y,
                -(ELBOW_REST_DEG - feature.leftElbowAngle) * ELBOW_SCALE));
        commands.add(command(
                RobotMotor.RIGHT_ELBOW_Y,
                -(ELBOW_REST_DEG - feature.rightElbowAngle) * ELBOW_SCALE));

        return commands;
    }

    /**
     * 由上臂的 3D 方向（身體座標系）求肩 Z / 肩 Y / 肩 X。
     *
     * <p>機器人肩的順序是「肩 Z（水平轉動）→ 肩 Y（往前上舉）→ 肩 X（側向外展）」，手臂朝下時方向為 (0,0,-1)。
     * 方向 = (前, 外, 上) 有兩種互補的解法，依手臂是往外還是往內選：
     * <ul>
     *   <li><b>往外或垂直（外 ≥ 0）</b>：肩 Z = 0，Ry(y)·Rx(x) 後方向 = (-cos x·sin y, sin x, -cos x·cos y)，
     *       反解 x = asin(外)、y = atan2(-前, -上)。</li>
     *   <li><b>往內（外 &lt; 0）</b>：肩 X 只能外展（-3~100）做不到內收，改用肩 Z 把往前舉的手臂水平轉向胸前：
     *       方向 = (-sin y·cos z, -sin y·sin z, -cos y)，反解 h = √(前²+外²)，y = atan2(-h, -上)，z = atan2(外, 前)
     *       （z 為負＝內收，超過 -85 由夾位處理）。</li>
     * </ul>
     * 兩個解在外 = 0 時相等，所以切換是連續的。y 超過 70（往後上方）時取離得近的合法一邊（範圍 -200~70）。
     */
    private void addArm3d(List<RobotCommand> commands, int shoulderZ, int shoulderX, int shoulderY,
                          float forward, float out, float up) {
        float x;
        float y;
        float z = 0f;
        if (out >= 0f) {
            x = (float) Math.toDegrees(Math.asin(Math.min(1f, out)));
            y = (float) Math.toDegrees(Math.atan2(-forward, -up));
        } else {
            x = 0f;
            float h = (float) Math.sqrt(forward * forward + out * out);
            y = (float) Math.toDegrees(Math.atan2(-h, -up));
            z = (float) Math.toDegrees(Math.atan2(out, forward));
        }
        // 合法範圍 -200~70，另一種表示是 y-360。超出上限時取離得近的一邊夾位：
        // 往後上方不太高（70~115）夾在 70；接近舉過頭（>115）換成 y-360（約 -200~-245，之後夾成 -200）
        if (y > SHOULDER_MAX_DEG) {
            y = y > 115f ? y - 360f : SHOULDER_MAX_DEG;
        }
        commands.add(command(shoulderZ, z));
        commands.add(command(shoulderX, x));
        commands.add(command(shoulderY, y));
    }

    /**
     * 把一隻手臂的抬升角拆成肩 X / 肩 Y 兩個指令。
     *
     * @param armAngle PoseAnalyzer 的手臂抬升角（髖-肩-肘）
     */
    private void addArm(List<RobotCommand> commands, int shoulderX, int shoulderY, float armAngle) {
        float lift = Math.max(0f, armAngle - ARM_REST_DEG) * ARM_SCALE;
        float x;
        float y;
        if (lift <= ARM_HORIZONTAL_DEG) {
            x = lift;
            y = 0f;
        } else {
            float t = Math.min(1f, (lift - ARM_HORIZONTAL_DEG) / (ARM_OVERHEAD_DEG - ARM_HORIZONTAL_DEG));
            x = ARM_HORIZONTAL_DEG * (1f - t);
            y = SHOULDER_Y_OVERHEAD_DEG * t;
        }
        commands.add(command(shoulderX, x));
        commands.add(command(shoulderY, y));
    }

    /** 肢體分組：頭 / 左臂 / 右臂，供 DanceScriptBuilder 依各肢體可信度過濾指令。 */
    public static final int LIMB_HEAD = 0, LIMB_LEFT_ARM = 1, LIMB_RIGHT_ARM = 2, LIMB_NONE = -1;

    /** 這顆馬達屬於哪個肢體；不是 RobotMapper 驅動的馬達回傳 {@link #LIMB_NONE}。 */
    public static int limbOf(int motorId) {
        switch (motorId) {
            case RobotMotor.NECK_YAW:
            case RobotMotor.NECK_PITCH:
                return LIMB_HEAD;
            case RobotMotor.LEFT_SHOULDER_Z:
            case RobotMotor.LEFT_SHOULDER_Y:
            case RobotMotor.LEFT_SHOULDER_X:
            case RobotMotor.LEFT_ELBOW_Y:
                return LIMB_LEFT_ARM;
            case RobotMotor.RIGHT_SHOULDER_Z:
            case RobotMotor.RIGHT_SHOULDER_Y:
            case RobotMotor.RIGHT_SHOULDER_X:
            case RobotMotor.RIGHT_ELBOW_Y:
                return LIMB_RIGHT_ARM;
            default:
                return LIMB_NONE;
        }
    }

    /**
     * 各馬達的速度上限（度/秒）。
     *
     * <p>官方 NUWA Robot SDK：{@code ctlMotor(..., setSpeedInDegreePerSec)} 的範圍是 0 ~ 200，
     * 官方沒有公布各馬達更細的實際極限，所以全部夾在 200。
     * 頭上下（NECK_Y）沿用模擬器 hardware.xml 的 velocityLimit 120，比較保守。
     * 模擬器的其他 velocityLimit（頭左右 300、肩 Y 360）超過 API 上限，不採用。
     */
    public static float maxSpeedFor(int motorId) {
        return motorId == RobotMotor.NECK_PITCH ? 120f : SDK_MAX_SPEED_DEG_PER_SEC;
    }

    /** 官方 SDK 允許的最大速度參數（度/秒）。 */
    public static final float SDK_MAX_SPEED_DEG_PER_SEC = 200f;

    /**
     * 查詢某顆馬達容許的最小角度。
     *
     * <p>{@link DanceScriptPlayer} 在機器人端再夾一次位用：
     * 就算腳本檔被手動改壞，也不會送出超過這個範圍的角度。
     *
     * @param motorId 馬達 id
     * @return 最小角度（度）；不是 RobotMapper 會驅動的馬達時回傳 NaN
     */
    public static float minDegFor(int motorId) {
        switch (motorId) {
            case RobotMotor.NECK_YAW:
                return NECK_YAW_MIN_DEG;
            case RobotMotor.NECK_PITCH:
                return NECK_PITCH_MIN_DEG;
            case RobotMotor.LEFT_SHOULDER_Y:
            case RobotMotor.RIGHT_SHOULDER_Y:
                return SHOULDER_MIN_DEG;
            case RobotMotor.LEFT_SHOULDER_X:
            case RobotMotor.RIGHT_SHOULDER_X:
                return SHOULDER_X_MIN_DEG;
            case RobotMotor.LEFT_SHOULDER_Z:
            case RobotMotor.RIGHT_SHOULDER_Z:
                return SHOULDER_Z_MIN_DEG;
            case RobotMotor.LEFT_ELBOW_Y:
            case RobotMotor.RIGHT_ELBOW_Y:
                return ELBOW_MIN_DEG;
            default:
                return Float.NaN;
        }
    }

    /**
     * 查詢某顆馬達容許的最大角度。
     *
     * @param motorId 馬達 id
     * @return 最大角度（度）；不是 RobotMapper 會驅動的馬達時回傳 NaN
     */
    public static float maxDegFor(int motorId) {
        switch (motorId) {
            case RobotMotor.NECK_YAW:
                return NECK_YAW_MAX_DEG;
            case RobotMotor.NECK_PITCH:
                return NECK_PITCH_MAX_DEG;
            case RobotMotor.LEFT_SHOULDER_Y:
            case RobotMotor.RIGHT_SHOULDER_Y:
                return SHOULDER_MAX_DEG;
            case RobotMotor.LEFT_SHOULDER_X:
            case RobotMotor.RIGHT_SHOULDER_X:
                return SHOULDER_X_MAX_DEG;
            case RobotMotor.LEFT_SHOULDER_Z:
            case RobotMotor.RIGHT_SHOULDER_Z:
                return SHOULDER_Z_MAX_DEG;
            case RobotMotor.LEFT_ELBOW_Y:
            case RobotMotor.RIGHT_ELBOW_Y:
                return ELBOW_MAX_DEG;
            default:
                return Float.NaN;
        }
    }

    /**
     * 建立一個夾位後的馬達指令。
     *
     * @param motorId 馬達 id
     * @param degree  夾位前的目標角度
     * @return 夾到該馬達範圍內的 RobotCommand
     */
    private static RobotCommand command(int motorId, float degree) {
        // + 0f：把反號產生的 -0.0 變回 0.0，腳本 / log 才不會出現 "-0.0"
        return new RobotCommand(motorId,
                clamp(degree, minDegFor(motorId), maxDegFor(motorId)) + 0f, DEFAULT_SPEED_DEG_PER_SEC);
    }

    /**
     * 產生一組樣本 PoseFeature 供 RobotMapper 測試按鈕使用。
     *
     * <p>用途：不依賴真實影片，就能驗證映射層的輸出。
     * 樣本用的是<b>人體角度</b>（和 PoseAnalyzer 的輸出同一個尺度），
     * 不是機器人角度 —— 所以 neutral 要填休息值而不是 0。
     *
     * @return 樣本 PoseFeature 列表（含描述名稱）
     */
    public static List<SamplePose> buildSamples() {
        List<SamplePose> samples = new ArrayList<>();

        // Step1：中性姿勢＝站直、雙手自然下垂（映射後應該是全部 0 度）
        PoseFeature neutral = restPose();
        samples.add(new SamplePose("neutral", neutral));

        // Step2：雙手高舉過頭、手肘微彎
        PoseFeature bothHandsUp = restPose();
        bothHandsUp.leftArmAngle = 160f;
        bothHandsUp.rightArmAngle = 160f;
        bothHandsUp.leftElbowAngle = 120f;
        bothHandsUp.rightElbowAngle = 120f;
        samples.add(new SamplePose("both_hands_up", bothHandsUp));

        // Step3：頭右轉 + 右手平舉
        PoseFeature headRightRightHand = restPose();
        headRightRightHand.headYaw = 45f;
        headRightRightHand.rightArmAngle = 90f;
        headRightRightHand.rightElbowAngle = 100f;
        samples.add(new SamplePose("head_right_rh_up", headRightRightHand));

        // Step4：低頭 + 左手平舉
        PoseFeature headDownLeftHand = restPose();
        headDownLeftHand.headPitch = -20f;
        headDownLeftHand.leftArmAngle = 90f;
        headDownLeftHand.leftElbowAngle = 100f;
        samples.add(new SamplePose("pitch_down_lh_up", headDownLeftHand));

        return samples;
    }

    /**
     * 建立一個「站直、雙手自然下垂、正面平視」的人體特徵。
     *
     * @return 休息姿勢的 PoseFeature（映射後所有馬達皆為 0 度）
     */
    private static PoseFeature restPose() {
        PoseFeature rest = new PoseFeature();
        rest.leftArmAngle = ARM_REST_DEG;
        rest.rightArmAngle = ARM_REST_DEG;
        rest.leftElbowAngle = ELBOW_REST_DEG;
        rest.rightElbowAngle = ELBOW_REST_DEG;
        rest.headYaw = 0f;
        rest.headPitch = 0f;
        rest.confident = true;
        return rest;
    }

    /**
     * 把一組指令格式化成可讀摘要。
     *
     * @param commands RobotMapper 產出的指令列表
     * @return 每個指令一行描述
     */
    public static String describeCommands(List<RobotCommand> commands) {
        StringBuilder sb = new StringBuilder();
        for (RobotCommand cmd : commands) {
            sb.append(cmd.describe()).append('\n');
        }
        return sb.toString();
    }

    /**
     * 將數值限制在 [min, max] 範圍內。
     *
     * @param value 原始數值
     * @param min   容許的最小值（度）
     * @param max   容許的最大值（度）
     * @return 限制後的數值
     */
    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    /**
     * 一組可測試的樣本姿勢（含名稱）。
     */
    public static class SamplePose {
        /** 樣本名稱。 */
        public final String name;
        /** 樣本人體特徵。 */
        public final PoseFeature feature;

        SamplePose(String name, PoseFeature feature) {
            this.name = name;
            this.feature = feature;
        }

        /**
         * 回傳此樣本姿勢的可讀描述。
         *
         * @return 名稱與特徵描述
         */
        public String describe() {
            return name + ": " + feature.describe();
        }
    }
}
