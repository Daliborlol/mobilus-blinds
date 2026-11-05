plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "dev.dalibor.blinds"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.dalibor.blinds"
        // Android 11 is where Device Controls start, which keeps version checks to a minimum.
        minSdk = 30
        targetSdk = 36
        // The release workflow sets these from the tag and run number.
        versionCode = System.getenv("VERSION_CODE")?.toInt() ?: 1
        versionName = System.getenv("VERSION_NAME")?.removePrefix("v") ?: "dev"
    }

    signingConfigs {
        // Android only accepts updates signed with the same key, so published builds need a fixed
        // one. It's supplied through the environment and never stored in this repository.
        System.getenv("KEYSTORE_FILE")?.let { keystore ->
            create("published") {
                storeFile = file(keystore)
                storeType = "PKCS12"
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = "blinds"
                keyPassword = System.getenv("KEYSTORE_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            // Paho finds its WebSocket transport through ServiceLoader, which minifying breaks.
            isMinifyEnabled = false
            // Local builds fall back to the debug key so they install without extra setup.
            signingConfig = signingConfigs.findByName("published") ?: signingConfigs.getByName("debug")
        }
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2026.01.00"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    implementation(project(":gateway"))
}
