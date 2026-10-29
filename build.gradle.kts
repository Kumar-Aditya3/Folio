plugins {
    id("com.android.test") version "9.2.1" apply false
    // 1.5's new source-directory adapter produces literal provider(?) paths with
    // this project's legacy AGP/Kotlin DSL on Windows. Keep the compatible adapter.
    id("androidx.baselineprofile") version "1.4.1" apply false
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.compose.compiler) apply false
    alias(libs.plugins.compose.multiplatform) apply false
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
}

allprojects {
    group = "com.folio.reader"
    version = "1.0.1"
}

tasks.register("clean", Delete::class) {
    delete(rootProject.layout.buildDirectory)
}
