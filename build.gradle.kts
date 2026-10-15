// photo_cc — native Android PSD editor.
//
// Deliberately boring build: no AndroidX, no Compose, no annotation processors,
// no third-party runtime dependencies at all. That keeps the APK small, the
// build fast and the app free of anything that could phone home.
plugins {
    id("com.android.application") version "8.7.3" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
}
