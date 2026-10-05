plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.agentdeck"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.agentdeck"
        minSdk = 26
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
    kotlinOptions { jvmTarget = "17" }
    androidResources {
        // Keep the complete production/QA archive in the project, but do not package authoring
        // folders or documentation into the device APK.
        ignoreAssetsPattern += ":build-run:tooling:*.md:*.tsv:SHA256SUMS.txt:game-avatar.example.json"
    }
}

dependencies {
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    // Bundled, on-device model: face position + smile probability, no cloud upload.
    implementation("com.google.mlkit:face-detection:16.1.7")
    // Official MediaPipe hand model: 21 landmarks + live, fully on-device inference.
    implementation("com.google.mediapipe:tasks-vision:latest.release")
    testImplementation("junit:junit:4.13.2")
}
