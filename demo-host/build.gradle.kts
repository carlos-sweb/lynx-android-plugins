plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "dev.lynx.android.plugins.demo"
    compileSdk = 34
    defaultConfig {
        applicationId = "dev.lynx.android.plugins.demo"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation(project(":android-all"))
    implementation("org.lynxsdk.lynx:lynx:4.1.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
}
