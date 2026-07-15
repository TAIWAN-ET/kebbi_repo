package com.example.myapplication;

/**
 * 動作序列的來源。用 enum 取代 String，避免 "Video"/"VIDEO"/"vedio"/"cam" 這類拼寫漂移。
 */
public enum MotionSource {
    VIDEO,
    CAMERA,
    JSON
}
