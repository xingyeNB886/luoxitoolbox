@file:Suppress("UnstableApiUsage")

plugins {
    alias(libs.plugins.agp.lib)
    alias(libs.plugins.kotlin)
    alias(libs.plugins.refine)
}

android {
    namespace = "com.stellar.server"
    buildFeatures {
        buildConfig = false
        aidl = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
}

dependencies {
    implementation(project(":stellar-api"))

    implementation(libs.androidx.annotation)
    implementation(libs.gson)
    implementation(libs.dev.rikka.rikkax.parcelablelist)
    implementation(libs.rikka.shizuku.api)
    implementation(libs.rikka.shizuku.provider)

    implementation(libs.rikka.hidden.compat)
    compileOnly(libs.rikka.hidden.stub)
    implementation(libs.refine.runtime)
}
