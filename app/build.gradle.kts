plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "sk.brock.nahravac"
    compileSdk = 34

    defaultConfig {
        applicationId = "sk.brock.nahravac"
        minSdk = 26
        targetSdk = 34
        // V CI sa versionCode zvysuje podla cisla behu, aby sa APK dalo instalovat ako aktualizacia.
        versionCode = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1
        versionName = "0.4.1"
    }

    // Pevny podpisovy kluc (verejny "debug" kluc, nie tajomstvo), aby sa nove verzie instalovali ako aktualizacia.
    signingConfigs {
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
            storeType = "pkcs12"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("debug")
        }
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    // FileProvider + ContextCompat. Ziadne ine kniznice (JSON = org.json, siet = HttpURLConnection).
    implementation("androidx.core:core-ktx:1.13.1")
}
