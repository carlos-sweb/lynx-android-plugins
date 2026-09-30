plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.kapt")
}

android {
    namespace = "dev.lynx.android.plugins.maps"
    compileSdk = 34
    defaultConfig { minSdk = 24; consumerProguardFiles("consumer-rules.pro") }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    publishing { singleVariant("release") { withSourcesJar() } }
}

dependencies {
    api(project(":android-core"))
    implementation("org.maplibre.gl:android-sdk-opengl:11.8.0")
    implementation("androidx.lifecycle:lifecycle-runtime:2.8.7")
    implementation("androidx.core:core-ktx:1.13.1")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    kapt("org.lynxsdk.lynx:lynx-processor:4.1.0")
}

extra["mavenArtifactId"] = "lynx-android-maps"
extra["mavenDescription"] = "Offline MapLibre map element for Lynx on Android."
apply(from = rootProject.file("gradle/publish-library.gradle.kts"))
