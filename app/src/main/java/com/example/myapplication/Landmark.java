package com.example.myapplication;

/**
 * 單一人體關節點。MediaPipe / MoveNet / BlazePose / OpenPose 都共用此結構。
 * x, y 為畫面歸一化座標 (0.0 ~ 1.0)，z 為深度。
 *
 * 使用 MediaPipe 的兩個 confidence 欄位（不要合併成一個 score，避免日後混淆）：
 * - visibility：該關節點在畫面中可被看見的程度。
 * - presence：該關節點存在於場景中的信心值。
 *
 * 刻意使用 mutable public field（非 final）：方便 Gson 等 JSON 函式庫直接反序列化。
 * 本類別 100% 純 Java，不 import 任何 Android 類別，PC / 單元測試皆可直接重用。
 */
public class Landmark {
    public float x;
    public float y;
    public float z;
    public float visibility;
    public float presence;

    public Landmark() {
    }

    public Landmark(float x, float y, float z, float visibility, float presence) {
        this.x = x;
        this.y = y;
        this.z = z;
        this.visibility = visibility;
        this.presence = presence;
    }
}
