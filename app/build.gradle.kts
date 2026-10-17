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

    signingConfigs {
        create("stable") {
            storeFile = file(System.getenv("ANDROID_KEYSTORE_PATH")
                ?: error("ANDROID_KEYSTORE_PATH is not configured"))
            storePassword = System.getenv("ANDROID_KEYSTORE_PASSWORD")
                ?: error("ANDROID_KEYSTORE_PASSWORD is not configured")
            keyAlias = System.getenv("ANDROID_KEY_ALIAS")
                ?: error("ANDROID_KEY_ALIAS is not configured")
            keyPassword = System.getenv("ANDROID_KEY_PASSWORD")
                ?: error("ANDROID_KEY_PASSWORD is not configured")
        }
    }

    buildTypes {
        getByName("debug") {
            signingConfig = signingConfigs.getByName("stable")
        }
    }

    // Keep Java and Kotlin bytecode targets aligned, as in the reference workflow.
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
