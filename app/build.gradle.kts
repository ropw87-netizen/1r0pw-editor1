plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Persistent signing key (created automatically by the GitHub workflow, stored in the private repo).
val ks = rootProject.file("keystore/1r0pw.jks")

android {
    namespace = "com.r0pw.editor"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.r0pw.editor"
        minSdk = 26
        targetSdk = 34
        versionCode = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()
        versionName = "0.2.0"
    }

    signingConfigs {
        if (ks.exists()) {
            create("r0pw") {
                storeFile = ks
                storePassword = "r0pw-keystore-2026"
                keyAlias = "r0pw"
                keyPassword = "r0pw-keystore-2026"
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = if (ks.exists()) signingConfigs.getByName("r0pw") else signingConfigs.getByName("debug")
        }
        debug {
            if (ks.exists()) signingConfig = signingConfigs.getByName("r0pw")
        }
    }

    buildFeatures { compose = true }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    lint { abortOnError = false }
    packaging { resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" } }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.09.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.exifinterface:exifinterface:1.3.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
}
