plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.xyq.livetranslate"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.xyq.livetranslate"
        minSdk = 29
        targetSdk = 35
        versionCode = 21
        versionName = "2.0.0"
    }

    signingConfigs {
        // 固定的 debug 签名：不配的话每台机器/每次 CI 会现生成随机 debug.keystore，
        // 导致每次构建签名不同、无法覆盖安装。指向仓库内固定 keystore 保证签名一致。
        // 口令为公开固定值，仅用于本地/内测包，非机密。
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
