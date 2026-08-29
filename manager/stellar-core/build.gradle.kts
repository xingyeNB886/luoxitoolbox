@file:Suppress("UnstableApiUsage")

plugins {
    alias(libs.plugins.agp.lib)
    alias(libs.plugins.kotlin)
}

android {
    namespace = "roro.stellar.manager"
    buildFeatures {
        buildConfig = false
        prefab = true
    }
    defaultConfig {
        externalNativeBuild {
            cmake {
                arguments += "-DANDROID_STL=none"
                abiFilters += listOf("arm64-v8a")
            }
        }
    }
    externalNativeBuild {
        cmake {
            path = file("src/main/jni/CMakeLists.txt")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
}

dependencies {
    implementation(libs.kotlinx.coroutines.core)
}
