plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "it.amico.voidxfx"
    compileSdk = 34
    defaultConfig {
        applicationId = "it.amico.voidxfx"
        minSdk = 26
        targetSdk = 34
        versionCode = 5
        versionName = "3.0"
    }
    buildTypes {
        release { isMinifyEnabled = false }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
