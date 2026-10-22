plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "org.visualaid.obstacleguide"
    compileSdk = 35

    defaultConfig {
        applicationId = "org.visualaid.obstacleguide"
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"
        ndk {
            // Budget phones in this project are ARM. Dropping x86 keeps the APK smaller.
            abiFilters += listOf("armeabi-v7a", "arm64-v8a")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        viewBinding = true
        buildConfig = false
    }

    packaging {
        jniLibs {
            // Compress native libraries so the install file stays small enough to share.
            // They are extracted on install. The models are read through AssetManager, not mmap.
            useLegacyPackaging = true
        }
        resources {
            excludes += setOf(
                "META-INF/AL2.0",
                "META-INF/LGPL2.1",
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE",
                "META-INF/LICENSE.txt",
                "META-INF/NOTICE",
                "META-INF/NOTICE.txt",
            )
        }
    }

    testOptions {
        unitTests.isIncludeAndroidResources = false
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.lifecycle:lifecycle-service:2.8.7")

    val camera = "1.3.4"
    implementation("androidx.camera:camera-core:$camera")
    implementation("androidx.camera:camera-camera2:$camera")
    implementation("androidx.camera:camera-lifecycle:$camera")

    // Interpreter for the bundled walking detector and the relative-depth model.
    implementation("org.tensorflow:tensorflow-lite:2.16.1")

    // Optional metric depth. Used only when ARCore is already installed. No download.
    implementation("com.google.ar:core:1.45.0")

    // Offline wake word and commands. Apache License 2.0. No network and no API key.
    implementation("com.alphacephei:vosk-android:0.3.75")

    testImplementation("junit:junit:4.13.2")
}
