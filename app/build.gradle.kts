plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.amap.navi.demo"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.amap.navi.demo"
        minSdk = 23
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    // 公开签名配置（仓库内 keystore，无需保密）
    signingConfigs {
        create("release") {
            storeFile = rootProject.file("keystore/maparrow-release.jks")
            storePassword = "maparrow123"
            keyAlias = "maparrow"
            keyPassword = "maparrow123"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        viewBinding = true
    }

    packaging {
        // 部分三方 SDK 的 so 库/资源冲突处理
        jniLibs {
            pickFirsts += listOf("lib/armeabi-v7a/libc++_shared.so", "lib/arm64-v8a/libc++_shared.so")
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")

    // 固定版本，避免 latest.integration 导致构建不可复现
    implementation("com.amap.api:3dmap-location-search:11.2.000_loc11.2.000_sea9.8.0")
}
