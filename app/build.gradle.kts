plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.ipspeed"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.ipspeed"
        minSdk = 26
        targetSdk = 28
        versionCode = 2
        versionName = "1.0"
    }

    // Keep Java and Kotlin bytecode targets aligned, as in the reference workflow.
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
