package com.example.myapplication.model;

import java.util.Locale;

/**
 * One second-level keyframe extracted from pose analysis.
 */
public class KeyframeEntry {
    public int secondIndex;
    public long timeMs;
    public String eventType;
    public String robotMotion;
    public float amplitudePercent;
    public int sampleCount;

    public KeyframeEntry() {
    }

    public KeyframeEntry(int secondIndex, long timeMs, String eventType,
            String robotMotion, float amplitudePercent, int sampleCount) {
        this.secondIndex = secondIndex;
        this.timeMs = timeMs;
        this.eventType = eventType;
        this.robotMotion = robotMotion;
        this.amplitudePercent = amplitudePercent;
        this.sampleCount = sampleCount;
    }

    public String describe() {
        return String.format(Locale.US,
                "sec=%d t=%d event=%s motion=%s amp=%.0f%% samples=%d",
                secondIndex, timeMs, eventType, robotMotion, amplitudePercent, sampleCount);
    }
}
