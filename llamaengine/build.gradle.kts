plugins {
    alias(libs.plugins.android.library)
}

android {
    // android.util.Log in the engine returns quietly in plain JVM tests.
    testOptions { unitTests.isReturnDefaultValues = true }
    namespace = "ai.unalome.pocket.llamaengine"
    compileSdk = 36
    ndkVersion = "29.0.14206865"

    defaultConfig {
        minSdk = 29
        consumerProguardFiles("consumer-rules.pro")
        ndk {
            // Phones only: every supported device is arm64. The emulator image is arm64 too.
            abiFilters += "arm64-v8a"
        }
        externalNativeBuild {
            cmake {
                // The engine is always built optimised, also in debug builds: a debug llama.cpp is
                // too slow to try anything. Debuggable Kotlin is unaffected.
                arguments += listOf(
                    "-DCMAKE_BUILD_TYPE=Release",
                    // One shared C++ runtime: llama-common throws exceptions that the JNI layer catches.
                    "-DANDROID_STL=c++_shared",
                    "-DBUILD_SHARED_LIBS=ON",
                    // CPU only in this milestone. ggml's CPU backend is built in several variants
                    // (armv8.0 … armv9.2 with dotprod, i8mm, SVE, SME) and the best one for the
                    // phone is picked at run time.
                    "-DGGML_BACKEND_DL=ON",
                    "-DGGML_CPU_ALL_VARIANTS=ON",
                    "-DGGML_NATIVE=OFF",
                    "-DGGML_OPENMP=OFF",
                    "-DGGML_LLAMAFILE=OFF",
                    "-DGGML_VULKAN=OFF",
                    "-DGGML_OPENCL=OFF",
                    "-DLLAMA_BUILD_COMMON=ON",
                    "-DLLAMA_BUILD_TESTS=OFF",
                    "-DLLAMA_BUILD_TOOLS=OFF",
                    // libmtmd only (image input), not the rest of llama.cpp's tools. Video input
                    // stays off: it needs LLAMA_SUBPROCESS and an ffmpeg binary.
                    "-DLLAMA_BUILD_MTMD=ON",
                    "-DLLAMA_BUILD_EXAMPLES=OFF",
                    "-DLLAMA_BUILD_SERVER=OFF",
                    "-DLLAMA_BUILD_APP=OFF",
                    "-DLLAMA_OPENSSL=OFF",
                    "-DLLAMA_SUBPROCESS=OFF",
                )
            }
        }
    }
    externalNativeBuild {
        cmake {
            path("src/main/cpp/CMakeLists.txt")
            version = "3.31.6"
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    api(project(":chatcore"))
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
