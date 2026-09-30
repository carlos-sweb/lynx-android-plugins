plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "dev.lynx.android.plugins.core"
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

    publishing { singleVariant("release") { withSourcesJar() } }
}

dependencies {
    api("org.lynxsdk.lynx:lynx:4.1.0")
}

extra["mavenArtifactId"] = "lynx-android-core"
extra["mavenDescription"] = "Shared event bridge for Lynx Android plugins."
apply(from = rootProject.file("gradle/publish-library.gradle.kts"))
