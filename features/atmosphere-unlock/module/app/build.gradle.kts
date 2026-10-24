plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.ksp) // runs the api/ksp-xposed processor — required as an Xposed module
}

android {
    namespace = "dev.pong.evo.atmosphere"
    compileSdk = 36
    minSdk = 30 // setup.md §4.1 — classic-Xposed floor is low, but this device uses API 30+
    targetSdk = 36

    buildFeatures { buildConfig = true } // REQUIRED on AGP 8+ (setup.md §4.2)

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17 // Java 17+ required since YukiHookAPI 1.2.0
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

// Dependency block — spec §8.1, PRESERVED VERBATIM (api and ksp-xposed match one-to-one). NO EDITS.
dependencies {
    compileOnly(libs.xposed.api)
    implementation(libs.yukihook.api)
    ksp(libs.yukihook.ksp)
}
