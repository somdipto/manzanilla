plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "com.agentdeck.hotel"
    compileSdk = 34
    defaultConfig {
        fun injected(name: String): String {
            val env = System.getenv(name)
            if (!env.isNullOrBlank()) return env
            val prop = project.findProperty(name) as String?
            if (!prop.isNullOrBlank()) return prop
            val file = rootProject.file("local.properties")
            if (!file.exists()) return ""
            return file.readLines().firstOrNull { it.substringBefore("#").trim().startsWith("$name=") }
                ?.substringAfter("=")?.trim().orEmpty()
        }
        fun javaString(value: String) = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
        applicationId = "dev.orange.hotel"
        minSdk = 26
        targetSdk = 34
        // Installed 10.0.2 is versionCode 12. Keep CI monotonic, never below that.
        versionCode = maxOf(System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 13, 13)
        buildConfigField("String", "BACKEND_URL", javaString(System.getenv("ORANGE_BACKEND_URL") ?: ""))
        buildConfigField("String", "DEVICE_TOKEN", javaString(System.getenv("ORANGE_DEVICE_TOKEN") ?: ""))
        buildConfigField("String", "FEED_URL", javaString(project.findProperty("feedUrl") as String? ?: "https://api.github.com/repos/somdipto/manzanilla/releases/latest"))
        buildConfigField("String", "FISH_API_KEY", javaString(injected("FISH_API_KEY")))
        buildConfigField("String", "FISH_AGENT_ID", javaString(injected("FISH_AGENT_ID")))
        buildConfigField("String", "FISH_VOICE_ID", javaString(injected("FISH_VOICE_ID")))
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
    implementation("io.livekit:livekit-android:2.29.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
tasks.withType<org.gradle.api.tasks.testing.Test>().configureEach {
    doFirst {
        classpath = files(classpath.files.filter { !it.path.endsWith("android.jar") })
    }
}
