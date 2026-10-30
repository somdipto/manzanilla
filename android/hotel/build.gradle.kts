plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
fun javaString(value: String) = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
val distribution = (project.findProperty("distribution") as String?) ?: "personal-test"
require(distribution in setOf("personal-test", "hotel")) {
    "distribution must be personal-test or hotel"
}
val metadataAsset = if (distribution == "hotel") "hotel-release-metadata.json" else "release-metadata.json"
android {
    namespace = "com.agentdeck.hotel"
    compileSdk = 34
    // Private same-signer instrumentation is used only for disposable release-upgrade tests.
    testBuildType = if (project.hasProperty("upgradeFixture")) "release" else "debug"
    defaultConfig {
        applicationId = "dev.orange.hotel"
        minSdk = 26
        targetSdk = 34
        testInstrumentationRunner = if (project.hasProperty("upgradeFixture"))
            "com.agentdeck.hotel.UpgradeDataFixture" else "com.agentdeck.hotel.VoiceLifecycleFixture"
        // Increment explicitly for every published APK; never derive identity from a CI run.
        versionCode = rootProject.file("VERSION_CODE").readText().trim().toInt().also {
            require(it > 34) { "Release candidates must supersede published code 34" }
        }
        buildConfigField("String", "FEED_URL", javaString(project.findProperty("feedUrl") as String? ?: "https://github.com/somdipto/manzanilla/releases/latest/download/$metadataAsset"))
        buildConfigField("String", "LEGACY_FEED_URL", javaString(project.findProperty("legacyFeedUrl") as String? ?: "https://api.github.com/repos/somdipto/manzanilla/releases/latest"))
        buildConfigField("String", "DISTRIBUTION", javaString(distribution))
        // Credentials and voice configuration belong to the user after installation,
        // never to an APK, environment variable, Gradle property or local.properties.
        manifestPlaceholders["distribution"] = distribution
        manifestPlaceholders["cleartext"] = if (project.hasProperty("feedUrl") || project.hasProperty("legacyFeedUrl")) "true" else "false"
        versionName = rootProject.file("VERSION").readText().trim()
    }
    if (project.hasProperty("installerConsentFixture")) {
        // Private debug QA must exercise Android consent, not an eligible silent self-update.
        sourceSets.getByName("debug").manifest.srcFile("src/installerConsent/AndroidManifest.xml")
    }
    androidResources { noCompress += "wav" }
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
    implementation("org.msgpack:msgpack-core:0.9.9")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    implementation("com.android.tools.build:apksig:8.2.2")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
tasks.withType<org.gradle.api.tasks.testing.Test>().configureEach {
    doFirst {
        classpath = files(classpath.files.filter { !it.path.endsWith("android.jar") })
    }
}
