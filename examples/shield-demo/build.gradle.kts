import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// The site key and the demo backend come from local.properties (not committed):
//   shield.siteKey=pub_xxxxxxxxx
//   shield.demoBackend=http://10.0.2.2:8787
val local = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}
fun localValue(key: String, default: String): String =
    (local.getProperty(key) ?: default).replace("\\", "\\\\").replace("\"", "\\\"")

android {
    namespace = "net.findip.shield.demo"
    compileSdk = 35

    defaultConfig {
        // Register this id on your Shield app site (Settings → Apps & keys).
        applicationId = "net.findip.shield.demo"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"
        buildConfigField("String", "SHIELD_SITE_KEY", "\"${localValue("shield.siteKey", "")}\"")
        // 10.0.2.2 is the computer running the emulator.
        buildConfigField("String", "DEMO_BACKEND", "\"${localValue("shield.demoBackend", "http://10.0.2.2:8787")}\"")
    }

    buildFeatures {
        buildConfig = true
        compose = true
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
    // The SDK from this repository. In your app: implementation("net.findip:shield-android:1.0.0")
    implementation(project(":shield"))

    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
}
