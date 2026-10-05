package com.example.myapplication.robot;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Locale;

/**
 * Maps dance events to robot motions.
 */
public final class MotionPoseMap {

    private MotionPoseMap() {
    }

    /**
     * Builds a stable event-to-motion mapping.
     *
     * <p>Preference order:
     * 1. Match obvious keywords in motion names.
     * 2. Fill any remaining events with a deterministic fallback order.
     */
    public static Map<String, String> buildEventToMotionMap(List<String> motions) {
        Map<String, String> eventToMotion = new LinkedHashMap<>();
        if (motions == null || motions.isEmpty()) {
            return eventToMotion;
        }

        List<String> unmatched = new ArrayList<>(motions);
        assignFirstMatch(eventToMotion, unmatched, "LEFT_HAND_UP",
                "left", "lh", "left_hand", "left hand", "wave_l");
        assignFirstMatch(eventToMotion, unmatched, "RIGHT_HAND_UP",
                "right", "rh", "right_hand", "right hand", "wave_r");
        assignFirstMatch(eventToMotion, unmatched, "BOTH_HANDS_UP",
                "both", "hands", "double", "up", "raise");
        assignFirstMatch(eventToMotion, unmatched, "LEAN_LEFT",
                "lean_left", "tilt_left", "left lean");
        assignFirstMatch(eventToMotion, unmatched, "LEAN_RIGHT",
                "lean_right", "tilt_right", "right lean");

        String[] fallbackOrder = {
                "LEFT_HAND_UP", "RIGHT_HAND_UP", "BOTH_HANDS_UP", "LEAN_LEFT", "LEAN_RIGHT"
        };
        for (String motion : unmatched) {
            for (String event : fallbackOrder) {
                if (!eventToMotion.containsKey(event)) {
                    eventToMotion.put(event, motion);
                    break;
                }
            }
        }
        return eventToMotion;
    }

    /**
     * Returns the event type for one motion name.
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

    private static void assignFirstMatch(Map<String, String> eventToMotion, List<String> motions,
            String event, String... hints) {
        if (eventToMotion.containsKey(event)) {
            return;
        }
        for (int i = 0; i < motions.size(); i++) {
            String motion = motions.get(i);
            String lower = motion.toLowerCase(Locale.US);
            if (containsAny(lower, hints)) {
                eventToMotion.put(event, motion);
                motions.remove(i);
                return;
            }
        }
    }

    private static boolean containsAny(String text, String... hints) {
        for (String hint : hints) {
            if (text.contains(hint)) {
                return true;
            }
        }
        return false;
    }
}
