plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "io.laelaps.zygextdensity"
    compileSdk = 36
    ndkVersion = "30.0.15729638"

    defaultConfig {
        minSdk = 28
        ndk { abiFilters += "arm64-v8a" }
        externalNativeBuild {
            cmake {
                arguments += "-DANDROID_STL=c++_static"
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.28.0+"
        }
    }

    // prefab is enabled to consume the Dobby prebuilt native package (Android build of the
    // thread-safe / PAC-aware inline hooker that LSPlant is tested against).
    buildFeatures {
        prefab = true
    }
}

dependencies {
    implementation("io.github.vvb2060.ndk:dobby:1.2")
}
