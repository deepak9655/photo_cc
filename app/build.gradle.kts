plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.strata.editor"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.strata.editor"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
        resourceConfigurations += listOf("en")
    }

    signingConfigs {
        // Release signing is optional: CI signs when the four environment
        // variables are present, and otherwise ships an unsigned release APK
        // alongside the debug one.
        val storePath = System.getenv("STRATA_KEYSTORE")
        if (storePath != null) {
            create("release") {
                storeFile = file(storePath)
                storePassword = System.getenv("STRATA_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("STRATA_KEY_ALIAS")
                keyPassword = System.getenv("STRATA_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release")
        }
        debug {
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

    lint {
        abortOnError = false
    }

    packaging {
        resources.excludes += setOf("META-INF/*.kotlin_module")
    }
}

dependencies {
    // Intentionally empty. Everything the app needs is in the Android framework.
}
