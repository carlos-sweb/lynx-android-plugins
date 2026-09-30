plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "dev.lynx.android.plugins.geolocation"
    compileSdk = 34
    defaultConfig { minSdk = 24; consumerProguardFiles("consumer-rules.pro") }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    publishing { singleVariant("release") { withSourcesJar() } }
}

dependencies {
    api(project(":android-core"))
    implementation("androidx.core:core-ktx:1.13.1")
}

extra["mavenArtifactId"] = "lynx-android-geolocation"
extra["mavenDescription"] = "Foreground geolocation connector for Lynx on Android."
apply(from = rootProject.file("gradle/publish-library.gradle.kts"))
