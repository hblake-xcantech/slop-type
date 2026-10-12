// llamabridge: Android library wrapping llama.cpp (static) behind a tiny JNI API.
// Kotlin support comes from AGP 9's built-in Kotlin (same as app/build.gradle.kts), so no
// org.jetbrains.kotlin.android plugin is applied here.
plugins {
    id("com.android.library")
}

android {
    namespace = "dev.sloptype.llamabridge"
    compileSdk = 37
    ndkVersion = "28.0.13004108"

    defaultConfig {
        minSdk = 28
        consumerProguardFiles("consumer-rules.pro")
        ndk {
            abiFilters.clear()
            abiFilters += "arm64-v8a"
        }
        externalNativeBuild {
            cmake {
                arguments += listOf(
                    "-DANDROID_STL=c++_shared",
                    "-DGGML_LLAMAFILE=OFF",
                    "-DCMAKE_BUILD_TYPE=Release",  // AGP defaults to RelWithDebInfo (-O2 -g)
                )
                cppFlags += "-O3"
                cFlags += "-O3"
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            isJniDebuggable = false
        }
        debug {
            isJniDebuggable = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        abortOnError = false
    }
}

dependencies {
    testImplementation(kotlin("test"))
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit")
}
