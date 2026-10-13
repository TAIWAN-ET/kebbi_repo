package com.example.myapplication.pc;

import com.example.myapplication.analysis.FaceExpressionAnalyzer;
import com.example.myapplication.io.FaceIO;
import com.example.myapplication.model.FaceExpression;
import com.example.myapplication.model.FaceFrame;

import java.io.File;
import java.io.IOException;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 用標註過的臉部 blendshape CSV 評估 {@link FaceExpressionAnalyzer}，印出混淆矩陣。
 *
 * <p>用途：調 {@code FaceExpressionAnalyzer} 的門檻時量準確度。CSV 由 {@code pc/eval_face.py} 產生
 * （一張照片一行，多一個 label 欄）。
 *
 * <pre>
 * java -cp pc/build/classes com.example.myapplication.pc.FaceExpressionEval out/face_eval.csv
 * </pre>
 */
public final class FaceExpressionEval {

    private FaceExpressionEval() {
    }

    public static void main(String[] args) throws IOException {
        if (args.length < 1) {
            System.err.println("usage: FaceExpressionEval <labelled_blendshapes.csv>");
            System.exit(2);
        }
        List<FaceFrame> frames = FaceIO.readCsv(new File(args[0]));
        FaceExpression[] all = FaceExpression.values();
        Map<FaceExpression, int[]> matrix = new EnumMap<>(FaceExpression.class);
        for (FaceExpression e : all) {
            matrix.put(e, new int[all.length]);
        }

        int total = 0;
        int correct = 0;
        int unknownLabel = 0;
        for (FaceFrame frame : frames) {
            FaceExpression truth = parseLabel(frame.label);
            if (truth == null || !frame.detected) {
                unknownLabel += truth == null ? 1 : 0;
                continue;
            }
            FaceExpression predicted = FaceExpressionAnalyzer.classify(frame);
            matrix.get(truth)[predicted.ordinal()]++;
            total++;
            correct += truth == predicted ? 1 : 0;
        }

        StringBuilder sb = new StringBuilder(String.format(Locale.US, "%-10s", "true\\pred"));
        for (FaceExpression e : all) {
            sb.append(String.format(Locale.US, "%10s", e.name()));
        }
        sb.append(String.format(Locale.US, "%10s", "recall"));
        System.out.println(sb);
        for (FaceExpression truth : all) {
            int[] row = matrix.get(truth);
            int rowSum = 0;
            sb = new StringBuilder(String.format(Locale.US, "%-10s", truth.name()));
            for (int count : row) {
                sb.append(String.format(Locale.US, "%10d", count));
                rowSum += count;
            }
            sb.append(rowSum == 0 ? "         -"
                    : String.format(Locale.US, "%10.2f", row[truth.ordinal()] / (float) rowSum));
            System.out.println(sb);
        }
        sb = new StringBuilder(String.format(Locale.US, "%-10s", "precision"));
        for (FaceExpression predicted : all) {
            int colSum = 0;
            for (FaceExpression truth : all) {
                colSum += matrix.get(truth)[predicted.ordinal()];
            }
            sb.append(colSum == 0 ? "         -" : String.format(Locale.US, "%10.2f",
                    matrix.get(predicted)[predicted.ordinal()] / (float) colSum));
        }
        System.out.println(sb);
        System.out.println(String.format(Locale.US, "accuracy %.3f (%d/%d)%s", total == 0 ? 0f : correct / (float) total,
                correct, total, unknownLabel > 0 ? ", skipped " + unknownLabel + " rows with unknown label" : ""));
    }

    /** 接受列舉名稱或常見資料集的寫法（happiness、surprise、anger、sadness）。 */
    static FaceExpression parseLabel(String label) {
        switch (label.trim().toLowerCase(Locale.US)) {
            case "neutral":
                return FaceExpression.NEUTRAL;
            case "happy":
            case "happiness":
            case "joy":
                return FaceExpression.HAPPY;
            case "surprised":
            case "surprise":
                return FaceExpression.SURPRISED;
            case "angry":
            case "anger":
                return FaceExpression.ANGRY;
            case "sad":
            case "sadness":
                return FaceExpression.SAD;
            default:
                return null;
        }
    }
}
