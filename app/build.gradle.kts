plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.samkeyboard"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.samkeyboard"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
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
    // Dịch trên máy (Google ML Kit). Mỗi ngôn ngữ tải 1 lần (~30MB) rồi dịch offline.
    implementation("com.google.mlkit:translate:17.0.3")
}
