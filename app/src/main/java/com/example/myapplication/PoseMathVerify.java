package com.example.myapplication;

import java.io.File;

/**
 * PoseMath / PoseAnalyzer 的純 Java 驗證工具。不依賴 Android、不接 Robot、不開 Camera。
 *
 * 用法（PC 上直接 javac + java）：
 *   javac -d out app/src/main/java/com/example/myapplication/*.java
 *   java  -cp out com.example.myapplication.PoseMathVerify <csv_path> [frame_index]
 *
 * 輸出例如：
 *   Frame 15
 *   Left elbow  : 91.8°
 *   Right elbow : 87.3°
 *   Shoulder    : 12.6°
 *   Head pitch  : -3.1°
 */
public final class PoseMathVerify {

    private PoseMathVerify() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.out.println("Usage: PoseMathVerify <csv_path> [frame_index]");
            return;
        }

        File csv = new File(args[0]);
        int frameIndex = args.length >= 2 ? Integer.parseInt(args[1]) : 0;

        MotionSequence sequence = PoseIO.readCsv(csv);
        System.out.println("Sequence: " + sequence.describe());

        if (sequence.getFrameCount() == 0) {
            System.out.println("ERROR: CSV 沒有解析出任何影格");
            return;
        }
        if (frameIndex < 0 || frameIndex >= sequence.getFrameCount()) {
            System.out.println("WARN: frameIndex " + frameIndex + " 超出範圍，改用中間影格");
            frameIndex = sequence.getFrameCount() / 2;
        }

        PoseFrame frame = sequence.frames.get(frameIndex);
        PoseFeature feature = PoseAnalyzer.analyzeFrame(frame);

        System.out.println("Frame " + frameIndex + " (ts=" + frame.timestampMs + "ms)");
        System.out.println("Left elbow  : " + r1(feature.leftElbowAngle) + "°");
        System.out.println("Right elbow : " + r1(feature.rightElbowAngle) + "°");
        System.out.println("Shoulder    : " + r1(feature.shoulderSlope) + "°");
        System.out.println("Head pitch  : " + r1(feature.headYaw) + "°");
    }

    private static String r1(float value) {
        return String.format(java.util.Locale.US, "%.1f", value);
    }
}
