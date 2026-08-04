package com.example.myapplication;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 機器人內建 motion 與「姿勢事件類型」的對應表。
 *
 * <p>用途：這是「數值（PoseFeature）→ 機器人動作」校正的單一入口。
 * 你實機看過每支 motion 實際跳什麼之後，把對應的事件類型寫進這裡，
 * 以後 PoseAnalyzer 推導出的 event 就能直接選到正確的 motion。
 *
 * <p>誰會呼叫它：
 * {@link MainActivity} 在播放舞蹈或選擇動作時呼叫。
 *
 * <p>不負責什麼：
 * 不計算任何角度或距離，不處理檔案 I/O，
 * 不處理 Android UI 或 MediaPipe 執行期，不認識任何機器人 SDK。
 * 只提供查表功能。
 */
public final class MotionPoseMap {

    private MotionPoseMap() {
    }

    /**
     * 預設對應（待實機校正）。
     *
     * <p>key = motion 名稱，value = 姿勢事件類型。
     * 先用已知測過的 5 支 motion 之外的情況留空，由 index 兜底。
     * 校正時把實際 motion 名稱填進去，例如：
     * DEFAULT_MAP.put("888_ML_Haveidea_20", "BOTH_HANDS_UP");
     */
    private static final Map<String, String> DEFAULT_MAP = new LinkedHashMap<>();

    static {
        // 這裡先留空，讓 buildMap 用 index 兜底；
        // 校正時把實際 motion 名稱填進去
    }

    /**
     * 依傳入的 motion 清單建立「事件類型 → motion 名稱」的查表。
     *
     * <p>Step1：遍歷 motions，將 DEFAULT_MAP 中有對應的 motion 加入查表。
     * Step2：剩餘未對應的 motion 按 index 順序兜底對應 5 種事件。
     *
     * @param motions 機器人內建 motion 清單
     * @return 事件類型 → motion 名稱的查表
     */
    public static Map<String, String> buildEventToMotionMap(List<String> motions) {
        Map<String, String> eventToMotion = new LinkedHashMap<>();
        String[] fallbackOrder = {
                "LEFT_HAND_UP", "RIGHT_HAND_UP", "BOTH_HANDS_UP", "LEAN_LEFT", "LEAN_RIGHT"
        };

        // Step1：將 DEFAULT_MAP 中有對應的 motion 加入查表
        List<String> unmatched = new ArrayList<>();
        for (String motion : motions) {
            String event = DEFAULT_MAP.get(motion);
            if (event != null) {
                eventToMotion.put(event, motion);
            } else {
                unmatched.add(motion);
            }
        }

        // Step2：剩餘未對應的 motion 按 index 順序兜底對應 5 種事件
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
     * 在選單中顯示用：回傳某 motion 目前的對應事件。
     *
     * <p>若 motion 在 DEFAULT_MAP 中有對應，回傳對應的事件類型；
     * 若沒有設定，顯示 index 兜底的事件。
     *
     * @param motions 機器人內建 motion 清單
     * @param motion  要查詢的 motion 名稱
     * @return 對應的事件類型字串；沒有對應時回傳 "(unmapped)"
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
