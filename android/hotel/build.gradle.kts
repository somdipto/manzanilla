plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "com.agentdeck.hotel"
    compileSdk = 34
    defaultConfig {
        applicationId = "dev.orange.hotel"
        minSdk = 26
        targetSdk = 34
        versionCode = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()
        buildConfigField("String", "BACKEND_URL", "\"" + (System.getenv("ORANGE_BACKEND_URL") ?: "") + "\"")
        buildConfigField("String", "DEVICE_TOKEN", "\"" + (System.getenv("ORANGE_DEVICE_TOKEN") ?: "") + "\"")
        buildConfigField("String", "FEED_URL", "\"" + (project.findProperty("feedUrl") as String? ?: "https://api.github.com/repos/somdipto/manzanilla/releases/latest") + "\"")
        manifestPlaceholders["cleartext"] = if (project.hasProperty("feedUrl")) "true" else "false"
        versionName = rootProject.file("VERSION").readText().trim()
    }
    buildFeatures { buildConfig = true }
    signingConfigs {
        create("pilot") {
            val keyPath = System.getenv("ORANGE_KEYSTORE")
            if (!keyPath.isNullOrBlank()) {
                storeFile = file(keyPath)
                storePassword = System.getenv("ORANGE_STORE_PASSWORD")
                keyAlias = System.getenv("ORANGE_KEY_ALIAS")
                keyPassword = System.getenv("ORANGE_KEY_PASSWORD")
            }
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            if (!System.getenv("ORANGE_KEYSTORE").isNullOrBlank()) signingConfig = signingConfigs.getByName("pilot")
        }
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
}
dependencies {
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}
