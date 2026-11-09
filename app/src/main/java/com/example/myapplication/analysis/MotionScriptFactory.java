package com.example.myapplication.analysis;

import com.example.myapplication.model.PoseFeature;
import com.example.myapplication.motion.MotionScript;
import com.example.myapplication.motion.MotionScriptBuilder;
import com.example.myapplication.motion.SamplingConfig;

import java.util.ArrayList;
import java.util.List;

/**
 * 把既有的 PoseFeature 序列轉成動作庫腳本（MotionScript）。
 *
 * <p>這是「舊資料流」與「動作庫」之間的唯一轉接層：
 * <pre>
 * PoseFeature（角度、手腕位置）→ 手腕 2D 軌跡 → MotionScriptBuilder → MotionScript
 * </pre>
 *
 * <p>只建立三條軌跡：
 * <ul>
 *   <li>RIGHT_WRIST：右手腕相對肩中心（畫圓、揮手、抬手）。</li>
 *   <li>LEFT_WRIST：左手腕相對肩中心。</li>
 *   <li>HEAD：headYaw / torsoLean 正規化後的 2D 軌跡（轉頭、點頭、搖頭）。</li>
 * </ul>
 *
 * <p>為什麼不用 33 個 landmark：動作庫只需要「末端執行器 + 時間」，
 * 這正是機器人馬達真正需要的資訊。其他關節留給既有的 PoseFeature / RobotMapper。
 */
public final class MotionScriptFactory {

    /** 手腕離肩中心超過這個倍率視為追蹤錯誤（單位：肩寬）。 */
    private static final float MAX_WRIST_DISTANCE = 4f;

    private MotionScriptFactory() {
    }

    /**
     * 由 PoseFeature 序列建立動作腳本。
     *
     * @param features 每幀人體特徵
     * @param times    每幀時間戳記（毫秒，必須與 features 等長）
     * @param source   來源名稱（例如 "video" / "camera"）
     * @return 動作腳本
     */
    public static MotionScript fromFeatures(List<PoseFeature> features, List<Long> times,
            String source) {
        MotionScriptBuilder builder = new MotionScriptBuilder(source, SamplingConfig.standard());
        if (features == null || times == null) {
            return builder.build();
        }
        addWristTrack(builder, "RIGHT_WRIST", features, times, false);
        addWristTrack(builder, "LEFT_WRIST", features, times, true);
        addHeadTrack(builder, features, times);
        return builder.build();
    }

    private static void addWristTrack(MotionScriptBuilder builder, String joint,
            List<PoseFeature> features, List<Long> times, boolean left) {
        List<Long> t = new ArrayList<>();
        List<Float> xs = new ArrayList<>();
        List<Float> ys = new ArrayList<>();
        int count = Math.min(features.size(), times.size());
        for (int i = 0; i < count; i++) {
            PoseFeature f = features.get(i);
            if (f == null) {
                continue;
            }
            boolean visible = left ? f.leftWristVisible : f.rightWristVisible;
            float x = left ? f.leftWristX : f.rightWristX;
            float y = left ? f.leftWristY : f.rightWristY;
            if (!visible || !isFinite(x) || !isFinite(y)
                    || Math.abs(x) > MAX_WRIST_DISTANCE || Math.abs(y) > MAX_WRIST_DISTANCE) {
                continue;
            }
            t.add(times.get(i));
            xs.add(x);
            ys.add(y);
        }
        if (t.size() >= 3) {
            builder.addTrack(joint, t, xs, ys);
        }
    }

    private static void addHeadTrack(MotionScriptBuilder builder,
            List<PoseFeature> features, List<Long> times) {
        List<Long> t = new ArrayList<>();
        List<Float> xs = new ArrayList<>();
        List<Float> ys = new ArrayList<>();
        int count = Math.min(features.size(), times.size());
        for (int i = 0; i < count; i++) {
            PoseFeature f = features.get(i);
            if (f == null) {
                continue;
            }
            // headYaw 以 45° 為 1 單位、torsoLean 以 30° 為 1 單位，方便共用門檻
            float x = f.headYaw / 45f;
            float y = f.torsoLean / 30f;
            if (!isFinite(x) || !isFinite(y)) {
                continue;
            }
            t.add(times.get(i));
            xs.add(x);
            ys.add(y);
        }
        if (t.size() >= 3) {
            builder.addTrack("HEAD", t, xs, ys);
        }
    }

    private static boolean isFinite(float value) {
        return !Float.isNaN(value) && !Float.isInfinite(value);
    }
}
