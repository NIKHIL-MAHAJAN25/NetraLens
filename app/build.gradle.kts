// This script at the top correctly reads your local.properties file.
import java.util.Properties
val localProperties = Properties()
val localPropertiesFile = rootProject.file("local.properties")
if (localPropertiesFile.exists()) {
    localProperties.load(localPropertiesFile.inputStream())
}

plugins {

    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    // The secrets plugin is here but we will not use it, per your request.
    alias(libs.plugins.google.android.libraries.mapsplatform.secrets.gradle.plugin)
}

android {
    namespace = "com.nikhil.netralens"
    // CRITICAL FIX: Updated SDK to match dependency requirements.
    compileSdk = 36

    defaultConfig {
        applicationId = "com.nikhil.netralens"
        minSdk = 24
        // BEST PRACTICE: Target SDK should match Compile SDK.
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        // KEPT AS REQUESTED: Using buildConfigField to handle the API key.
        buildConfigField("String", "apiKey", "\"${localProperties.getProperty("apiKey")}\"")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        // PROACTIVE FIX: Updated to Java 17, which is recommended for the latest AGP.
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        // This is necessary for the buildConfigField to work.
        buildConfig = true
    }
}

dependencies {
    implementation("com.google.mlkit:face-detection:16.1.7")
    implementation("com.google.android.gms:play-services-location:21.2.0")
    implementation("com.google.mediapipe:tasks-vision:0.10.14")
    // This BOM ensures all your Compose libraries are version-compatible, fixing the clickable crash.
    implementation(platform("com.google.firebase:firebase-bom:34.4.0"))
    implementation("com.google.firebase:firebase-analytics")
    implementation(platform(libs.androidx.compose.bom))

    // Core dependencies
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.espresso.core)
    // Add these for CameraX
    val cameraxVersion = "1.3.4" // Use a recent stable version
    implementation("androidx.camera:camera-core:${cameraxVersion}")
    implementation("androidx.camera:camera-camera2:${cameraxVersion}")
    implementation("androidx.camera:camera-lifecycle:${cameraxVersion}")
    implementation("androidx.camera:camera-view:${cameraxVersion}")
    // ML Kit (The "Reflex" Brain)
    implementation("com.google.mlkit:object-detection:17.0.1")
    implementation("com.google.mlkit:text-recognition:16.0.0")
    // Compose UI Dependencies
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    debugImplementation(libs.androidx.ui.tooling)

    // Using Material 3
    implementation(libs.androidx.material3)

    // Gemini AI dependency
    implementation(libs.generativeai)

    // Testing dependencies
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.test.manifest)
}