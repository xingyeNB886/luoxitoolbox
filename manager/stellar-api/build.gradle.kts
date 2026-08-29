@file:Suppress("UnstableApiUsage")

plugins {
    alias(libs.plugins.agp.lib)
    alias(libs.plugins.kotlin)
}

android {
    namespace = "roro.stellar.shared"
    buildFeatures {
        buildConfig = true
        aidl = true
    }
    defaultConfig {
        buildConfigField("int", "SERVER_API_VERSION", "103")
        buildConfigField("String", "SERVER_API_VERSION_NAME", "\"1.0.3\"")
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
}

dependencies {
    implementation(libs.androidx.annotation)
    implementation(libs.androidx.core.ktx)
    implementation(libs.dev.rikka.rikkax.parcelablelist)
}
