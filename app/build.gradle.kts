plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.example.myapplication"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        // 和舊的 Kebbi Dance（com.example.myapplication）分開，兩個可以同時裝在 Kebbi 上
        applicationId = "com.taiwanet.kebbilink"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // 固定的 debug 簽名：GitHub Actions 每次都是新機器，不固定的話每版簽名都不同，
    // Kebbi 上就不能 adb install -r 覆蓋更新。這把只用於 debug，不是正式發佈用。
    signingConfigs {
        getByName("debug") {
            storeFile = file("kebbi-debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("debug")
        }
        release {
            optimization {
                enable = false
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.jar", "*.aar"))))

    implementation(libs.activity.ktx)
    implementation(libs.appcompat)
    implementation(libs.constraintlayout)
    implementation(libs.material)
    // 鎖定版號：latest.release 會讓建置不可重現，Google 一改 API 就無預警爆掉。
    implementation("com.google.mediapipe:tasks-vision:0.10.29")
    testImplementation(libs.junit)
    androidTestImplementation(libs.espresso.core)
    androidTestImplementation(libs.ext.junit)
}
