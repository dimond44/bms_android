plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "ru.liferych.bms"
    compileSdk = 35

    defaultConfig {
        applicationId = "ru.liferych.bms"
        minSdk = 23
        targetSdk = 35
        versionCode = 64
        versionName = "0.2.4-owner-profile"
    }

    sourceSets {
        getByName("main").assets.srcDir(rootProject.file("config"))
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
    implementation("androidx.activity:activity-ktx:1.10.1")
    implementation("androidx.camera:camera-camera2:1.5.3")
    implementation("androidx.camera:camera-lifecycle:1.5.3")
    implementation("androidx.camera:camera-view:1.5.3")
    implementation("com.google.mlkit:barcode-scanning:17.3.0")
}
