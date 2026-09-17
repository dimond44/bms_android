import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) {
        file.inputStream().use { load(it) }
    }
}
val bmsApiKey = localProperties.getProperty("BMS_API_KEY", "").orEmpty()

android {
    namespace = "ru.liferych.bms"
    compileSdk = 35

    defaultConfig {
        applicationId = "ru.liferych.bms"
        minSdk = 23
        targetSdk = 35
        // Базовые значения; фактические версии задаются в productFlavors отдельно.
        versionCode = 1
        versionName = "0.0.0"
        buildConfigField("String", "BMS_API_KEY", "\"${bmsApiKey.replace("\\", "\\\\").replace("\"", "\\\"")}\"")
    }

    buildFeatures {
        buildConfig = true
        compose = true
    }

    flavorDimensions += "role"
    productFlavors {
        create("user") {
            dimension = "role"
            isDefault = true
            applicationId = "ru.liferych.bms"
            versionCode = 121
            versionName = "0.2.61"
            resValue("string", "app_name", "ЛИФЕРЫЧ BMS")
            buildConfigField("boolean", "IS_SERVICE", "false")
        }
        create("service") {
            dimension = "role"
            applicationId = "ru.liferych.bms.service"
            versionCode = 105
            versionName = "0.2.45"
            resValue("string", "app_name", "ЛИФЕРЫЧ Сервис")
            buildConfigField("boolean", "IS_SERVICE", "true")
        }
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
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.core:core-splashscreen:1.0.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("com.google.android.gms:play-services-location:21.3.0")
    implementation("androidx.exifinterface:exifinterface:1.3.7")
    implementation("androidx.camera:camera-camera2:1.5.3")
    implementation("androidx.camera:camera-lifecycle:1.5.3")
    implementation("androidx.camera:camera-view:1.5.3")
    implementation("com.google.mlkit:barcode-scanning:17.3.0")

    val room = "2.6.1"
    implementation("androidx.room:room-runtime:$room")
    implementation("androidx.room:room-ktx:$room")
    ksp("androidx.room:room-compiler:$room")

    implementation("androidx.work:work-runtime-ktx:2.9.1")

    // Jetpack Compose (new frontend only; legacy MainActivity stays View-based)
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    androidTestImplementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.navigation:navigation-compose:2.8.5")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")

    testImplementation("junit:junit:4.13.2")
    // Real org.json for JVM unit tests (Android stub is not mocked by default).
    testImplementation("org.json:json:20240303")
}
