import com.vanniktech.maven.publish.AndroidSingleVariantLibrary
import com.vanniktech.maven.publish.SonatypeHost

plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("com.vanniktech.maven.publish")
}

val sdkVersion = providers.gradleProperty("VERSION_NAME").get()

android {
    namespace = "net.findip.shield"
    compileSdk = 35

    defaultConfig {
        minSdk = 26
        consumerProguardFiles("consumer-rules.pro")
        buildConfigField("String", "SDK_VERSION", "\"$sdkVersion\"")
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    lint {
        abortOnError = true
        warningsAsErrors = false
    }
}

kotlin {
    // Every public declaration is deliberate; applies to the library, not its tests.
    explicitApi()
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    // The real org.json: android.jar only ships stubs for JVM unit tests.
    testImplementation("org.json:json:20240303")
}

// Maven Central (Sonatype Central Portal) as net.findip:shield-android.
// Signing runs only when the key is provided (the release workflow passes it);
// publishToMavenLocal works without it, which CI uses to check the publication.
mavenPublishing {
    configure(AndroidSingleVariantLibrary(variant = "release", sourcesJar = true, publishJavadocJar = true))
    publishToMavenCentral(SonatypeHost.CENTRAL_PORTAL)
    if (providers.gradleProperty("signingInMemoryKey").isPresent) {
        signAllPublications()
    }
    coordinates("net.findip", "shield-android", sdkVersion)
    pom {
        name.set("FindIP Shield for Android")
        description.set("A risk verdict on each signup, login and checkout in your Android app. No tracking, INTERNET permission only.")
        inceptionYear.set("2026")
        url.set("https://github.com/findip-net/findip-shield-android")
        licenses {
            license {
                name.set("MIT License")
                url.set("https://github.com/findip-net/findip-shield-android/blob/main/LICENSE")
                distribution.set("repo")
            }
        }
        developers {
            developer {
                id.set("findip")
                name.set("FindIP")
                email.set("info@findip.net")
                url.set("https://www.findip.net")
            }
        }
        scm {
            url.set("https://github.com/findip-net/findip-shield-android")
            connection.set("scm:git:git://github.com/findip-net/findip-shield-android.git")
            developerConnection.set("scm:git:ssh://git@github.com/findip-net/findip-shield-android.git")
        }
    }
}
