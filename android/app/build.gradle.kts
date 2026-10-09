plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.coucou.android"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.coucou.android"
        minSdk = 30
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        // English only for now: the values-xx folders stay in the repo but are not in the app.
        resourceConfigurations += listOf("en")
    }
    // English only for now: the kept values-xx folders are not in the app, so lint must not compare them
    // with the English strings (it would flag every string added or removed since they were written).
    lint { disable += listOf("MissingTranslation", "ExtraTranslation") }
    buildFeatures { compose = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    sourceSets["main"].java.srcDirs("src/main/kotlin")
    sourceSets["test"].java.srcDirs("src/test/kotlin")
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.10.01")
    implementation(composeBom)
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    // Scanning the pairing QR code. CameraX (androidx) shows the camera; zxing-core (Apache 2.0, pure Java) reads the
    // code. Neither talks to any server; the reasoning is in the commit message.
    val cameraX = "1.3.4"
    implementation("androidx.camera:camera-core:$cameraX")
    implementation("androidx.camera:camera-camera2:$cameraX")
    implementation("androidx.camera:camera-lifecycle:$cameraX")
    implementation("androidx.camera:camera-view:$cameraX")
    implementation("com.google.zxing:core:3.5.3")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303") // android.jar stubs org.json in JVM tests
}
