plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.ksp) // runs the api/ksp-xposed processor — required as an Xposed module
}

android {
    namespace = "dev.pong.evo.atmosphere"
    compileSdk = 36

    // The development SDK is the read-only Nix flake store (flake.nix: buildToolsVersions
    // = ["36.0.0"]); AGP's default build-tools (35.0.0) cannot be self-installed there.
    buildToolsVersion = "36.0.0"

    defaultConfig {
        minSdk = 30 // setup.md §4.1 — classic-Xposed floor is low, but this device uses API 30+
        targetSdk = 36
    }

    buildFeatures { buildConfig = true } // REQUIRED on AGP 8+ (setup.md §4.2)

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17 // Java 17+ required since YukiHookAPI 1.2.0
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

// Dependency block — spec §8.1, PRESERVED VERBATIM (api and ksp-xposed match one-to-one),
// PLUS one documented delta: androidx.annotation is required because YukiHookAPI's KSP
// processor emits `androidx.annotation.Keep` into generated sources while its own
// AndroidX deps are compileOnly and not exported to the module's classpath.
dependencies {
    compileOnly(libs.xposed.api)
    implementation(libs.yukihook.api)
    implementation(libs.androidx.annotation)
    ksp(libs.yukihook.ksp)
}
