plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.photocc.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.photocc.app"
        // Android 7.0 and up: covers essentially every phone still in use,
        // including the Android 16 devices this app was written for.
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "0.2.0"
        // no test runner dependency: the engine self-test is a plain main()
        resourceConfigurations += listOf("en")
    }

    buildTypes {
        release {
            // R8 on, resources shrunk: the whole app is a few hundred KB.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // CI signs with a generated key when one is provided, otherwise the
            // APK is left unsigned (and the debug APK is the one to sideload).
            signingConfig = if (System.getenv("PHOTO_CC_KEYSTORE") != null) {
                signingConfigs.create("ci") {
                    storeFile = file(System.getenv("PHOTO_CC_KEYSTORE"))
                    storePassword = System.getenv("PHOTO_CC_STORE_PASSWORD")
                    keyAlias = System.getenv("PHOTO_CC_KEY_ALIAS") ?: "photocc"
                    keyPassword = System.getenv("PHOTO_CC_KEY_PASSWORD") ?: System.getenv("PHOTO_CC_STORE_PASSWORD")
                }
            } else {
                null
            }
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

    buildFeatures {
        buildConfig = false
        resValues = false
    }

    packaging {
        resources.excludes += setOf("META-INF/*", "kotlin/**", "DebugProbesKt.bin")
    }

    // fail early on typos instead of at runtime
    lint {
        abortOnError = true
        warningsAsErrors = false
        checkDependencies = false
    }
}

// Runs the framework-free engine self-test on the JVM: `gradle engineTest`.
//
// EngineSelfTest is a plain `main()` — no JUnit, no test runner — and lives in
// the unit-test source set because it also needs the AWT-based text measurer
// that only exists on a desktop JVM. The task is wired defensively so a
// checkout without the unit-test source set still configures.
tasks.register<JavaExec>("engineTest") {
    group = "verification"
    description = "Runs the PSD engine self-tests on the JVM"
    dependsOn("compileDebugKotlin")
    dependsOn(tasks.matching { it.name == "compileDebugUnitTestKotlin" })
    classpath = files(
        layout.buildDirectory.dir("tmp/kotlin-classes/debug"),
        layout.buildDirectory.dir("tmp/kotlin-classes/debugUnitTest"),
        configurations.getByName("debugRuntimeClasspath"),
    )
    mainClass.set("com.photocc.app.core.EngineSelfTest")
    // The harness reads these; both default to the repository layout.
    environment("PHOTO_CC_FIXTURES", System.getenv("PHOTO_CC_FIXTURES") ?: "$projectDir/../tools/engine/fixtures")
    environment("PHOTO_CC_OUT", System.getenv("PHOTO_CC_OUT") ?: "$projectDir/../build/engine-test")
    systemProperty("java.awt.headless", "true")
}

dependencies {
    // intentionally none at runtime
}
