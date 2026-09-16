import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
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
    }

    flavorDimensions += "role"
    productFlavors {
        create("user") {
            dimension = "role"
            isDefault = true
            applicationId = "ru.liferych.bms"
            versionCode = 118
            versionName = "0.2.58"
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

    testImplementation("junit:junit:4.13.2")
}
