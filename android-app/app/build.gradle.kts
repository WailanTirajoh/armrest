plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

val appVersion = rootProject.file("../VERSION").readText().trim()
val buildNumber = providers.environmentVariable("GITHUB_RUN_NUMBER").orNull?.toInt() ?: 1
val releaseKeystore = providers.environmentVariable("ANDROID_KEYSTORE_PATH").orNull

android {
    namespace = "io.github.wailantirajoh.armrest"
    // Library Compose terbaru butuh compileSdk 37.
    compileSdk = 37

    // Bahasa app bisa dipilih per app (Android 13+): daftar bahasa dibuat dari folder values-*.
    androidResources {
        generateLocaleConfig = true
    }

    defaultConfig {
        applicationId = "io.github.wailantirajoh.armrest"
        minSdk = 26
        // Naik ke 37 setelah perubahan perilaku Android 17 untuk akses jaringan lokal dicek di M0.
        targetSdk = 36
        versionCode = buildNumber
        versionName = appVersion
    }

    signingConfigs {
        // Keystore tetap dari secret CI, supaya APK baru bisa menimpa yang lama.
        if (releaseKeystore != null) {
            create("release") {
                storeFile = file(releaseKeystore)
                storePassword = providers.environmentVariable("ANDROID_KEYSTORE_PASSWORD").get()
                keyAlias = providers.environmentVariable("ANDROID_KEY_ALIAS").get()
                keyPassword = providers.environmentVariable("ANDROID_KEY_PASSWORD").get()
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Tanpa secret (build lokal, PR dari fork) jatuh ke debug key.
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(project(":core"))
    implementation(platform(libs.compose.bom))
    implementation(libs.activity.compose)
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.coroutines.android)
    implementation(libs.camera.camera2)
    implementation(libs.camera.lifecycle)
    implementation(libs.camera.view)
    implementation(libs.mlkit.barcode)
    testImplementation(libs.junit)
}
