plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "ai.unalome.pocket"
    compileSdk = 36
    ndkVersion = "29.0.14206865" // strips the native libraries when packaging

    defaultConfig {
        applicationId = "ai.unalome.pocket"
        minSdk = 29
        targetSdk = 36
        versionCode = 2
        versionName = "0.2.1"
        ndk {
            // Phones are arm64; so is the emulator image used for checks.
            abiFilters += "arm64-v8a"
        }
    }

    // Release signing comes from outside the tree: the keystore path and its password are passed as
    // environment variables (scripts/build-release.sh reads the password from the macOS Keychain).
    // Without them the release APK is built unsigned.
    val releaseKeystore = System.getenv("UNALOME_KEYSTORE")?.let(::file)?.takeIf { it.isFile }
    val releasePassword = System.getenv("UNALOME_KEYSTORE_PASSWORD")
    signingConfigs {
        if (releaseKeystore != null && releasePassword != null) {
            create("release") {
                storeFile = releaseKeystore
                storePassword = releasePassword
                keyAlias = System.getenv("UNALOME_KEY_ALIAS") ?: "upload"
                keyPassword = releasePassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        jniLibs {
            // ggml loads its CPU backend (the best libggml-cpu-*.so variant for the phone) with
            // dlopen from the native library directory, so the libraries must be extracted there.
            useLegacyPackaging = true
        }
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":features"))
    implementation(project(":modelcatalog"))
    implementation(project(":llamaengine"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.kotlinx.coroutines.android)
}
