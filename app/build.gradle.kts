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
    }

    buildTypes {
        debug {
            // Shrunk so the arm64 file is the one to copy to a phone. Still signed with the debug key.
            isMinifyEnabled = true
            isShrinkResources = true
            isDebuggable = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    splits {
        abi {
            isEnable = true
            reset()
            // One APK per ARM ABI. The Samsung test phone uses arm64-v8a.
            // x86 is omitted. abiFilters cannot be set at the same time as splits.
            include("armeabi-v7a", "arm64-v8a")
            isUniversalApk = false
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
            // Compress native libraries. They are extracted on install.
            // The models are read through AssetManager, not mmap.
            useLegacyPackaging = true
            excludes += setOf(
                "lib/x86/**",
                "lib/x86_64/**",
                "lib/armeabi/**",
                "lib/mips/**",
                "lib/mips64/**",
            )
            keepDebugSymbols.clear()
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

    lint {
        checkReleaseBuilds = false
        abortOnError = false
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
