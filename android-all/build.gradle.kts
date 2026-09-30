plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "dev.lynx.android.plugins"
    compileSdk = 34
    defaultConfig { minSdk = 24; consumerProguardFiles("consumer-rules.pro") }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation(project(":android-core"))
    implementation(project(":android-battery"))
    implementation(project(":android-camera"))
    implementation(project(":android-device"))
    implementation(project(":android-geolocation"))
    implementation(project(":android-network"))
    implementation(project(":android-vibration"))
}
