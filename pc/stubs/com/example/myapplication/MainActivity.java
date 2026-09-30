package com.example.myapplication;

/**
 * PC 編譯用空殼。
 *
 * <p>App 的純 Java 類別（model / io / analysis ...）為了 JavaDoc {@code {@link MainActivity}}
 * 而 import 了這個 Android 類別；PC 上沒有 Android，所以放一個空類別讓 javac 過得去。
 * 不會被執行，也不會打包進 APK。
 */
public class MainActivity {
}
