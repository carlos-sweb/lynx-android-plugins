plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "dev.lynx.android.plugins"
    compileSdk = 34

    defaultConfig {
        minSdk = 24
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    // Match the Lynx Android runtime used by the host app. This dependency is
    // intentionally explicit: this artifact is an Android-only bridge.
    implementation("org.lynxsdk.lynx:lynx:4.1.0")
    implementation("androidx.core:core-ktx:1.13.1")
}
