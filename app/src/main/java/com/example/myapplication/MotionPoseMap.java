package com.example.myapplication;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 機器人內建 motion 與「姿勢事件類型」的對應表。
 *
 * 這是「數值（PoseFeature）→ 機器人動作」校正的單一入口：
 * 你實機看過每支 motion 實際跳什麼之後，把對應的事件類型寫進這裡，
 * 以後 PoseAnalyzer 推導出的 event 就能直接選到正確的 motion。
 *
 * 預設先用 index 順序對應（與原本 chooseMotionForEvent 行為一致），
 * 待你一支一支校正後再覆寫 DEFAULT_MAP。
 */
public final class MotionPoseMap {

    private MotionPoseMap() {
    }

    /**
     * 預設對應（待實機校正）。key = motion 名稱，value = 姿勢事件類型。
     * 先用已知測過的 5 支 motion 之外的情況留空，由 index 兜底。
     */
    private static final Map<String, String> DEFAULT_MAP = new LinkedHashMap<>();

    static {
        // 這裡先留空，讓 buildMap 用 index 兜底；
        // 校正時把實際 motion 名稱填進去，例如：
        // DEFAULT_MAP.put("888_ML_Haveidea_20", "BOTH_HANDS_UP");
    }

    /**
     * 依傳入的 motion 清單建立「事件類型 → motion 名稱」的查表。
     * 有寫入 DEFAULT_MAP 的 motion 優先；其餘按 index 順序兜底對應 5 種事件。
     */
    public static Map<String, String> buildEventToMotionMap(List<String> motions) {
        Map<String, String> eventToMotion = new LinkedHashMap<>();
        String[] fallbackOrder = {
                "LEFT_HAND_UP", "RIGHT_HAND_UP", "BOTH_HANDS_UP", "LEAN_LEFT", "LEAN_RIGHT"
        };

        List<String> unmatched = new ArrayList<>();
        for (String motion : motions) {
            String event = DEFAULT_MAP.get(motion);
            if (event != null) {
                eventToMotion.put(event, motion);
            } else {
                unmatched.add(motion);
            }
        }

        int fi = 0;
        for (String motion : unmatched) {
            String event = fallbackOrder[fi % fallbackOrder.length];
            fi++;
            if (!eventToMotion.containsKey(event)) {
                eventToMotion.put(event, motion);
            }
        }
        return eventToMotion;
    }

    /**
     * 在選單中顯示用：回傳某 motion 目前的對應事件（沒設定就顯示 index 兜底）。
     */
    public static String eventForMotion(List<String> motions, String motion) {
        Map<String, String> eventToMotion = buildEventToMotionMap(motions);
        for (Map.Entry<String, String> entry : eventToMotion.entrySet()) {
            if (entry.getValue().equals(motion)) {
                return entry.getKey();
            }
        }
        return "(unmapped)";
    }
}
