plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.anpinime.projector"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.anpinime.projector"
        minSdk = 23
        targetSdk = 34
        versionCode = 19
        versionName = "1.9.2"
        manifestPlaceholders["appLabel"] = "AnPinime TV"
        manifestPlaceholders["homeOrientation"] = "sensorLandscape"

        ndk {
            abiFilters += listOf("armeabi-v7a", "arm64-v8a")
        }
    }

    buildFeatures {
        buildConfig = true
    }

    flavorDimensions += "device"
    productFlavors {
        create("projector") {
            dimension = "device"
            applicationId = "com.anpinime.projector"
            manifestPlaceholders["appLabel"] = "AnPinime TV"
            manifestPlaceholders["homeOrientation"] = "sensorLandscape"
            buildConfigField("boolean", "IS_PHONE_FLAVOR", "false")
        }
        create("phone") {
            dimension = "device"
            applicationId = "com.anpinime.phone"
            manifestPlaceholders["appLabel"] = "AnPinime Mobile"
            manifestPlaceholders["homeOrientation"] = "portrait"
            buildConfigField("boolean", "IS_PHONE_FLAVOR", "true")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Sign with debug key so the release APK installs directly on any projector/phone
            signingConfig = signingConfigs.getByName("debug")
        }
        debug {
            applicationIdSuffix = ".preview"
            versionNameSuffix = "-preview"
        }
    }

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    // A small per-device APK for projectors; keep a universal build for CI.
    splits {
        abi {
            isEnable = true
            reset()
            include("armeabi-v7a", "arm64-v8a")
            isUniversalApk = true
        }
    }
    packaging {
        jniLibs.useLegacyPackaging = true
    }
    androidResources {
        ignoreAssetsPattern = "anime_loading.gif:movie_loading.gif"
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.0.4")
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.webkit:webkit:1.10.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jellyfin.media3:media3-ffmpeg-decoder:1.3.1+2")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    implementation("androidx.media3:media3-exoplayer:1.3.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.3.1")
    implementation("androidx.media3:media3-exoplayer-dash:1.3.1")
    implementation("androidx.media3:media3-extractor:1.3.1")
    implementation("androidx.media3:media3-ui:1.3.1")
    implementation("androidx.media3:media3-datasource:1.3.1")
    implementation("org.jsoup:jsoup:1.17.2")
}
