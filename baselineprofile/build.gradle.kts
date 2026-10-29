plugins {
    id("com.android.test")
    alias(libs.plugins.kotlin.android)
    id("androidx.baselineprofile")
}

android {
    namespace = "com.folio.reader.baselineprofile"
    compileSdk = 37
    targetProjectPath = ":androidApp"
    defaultConfig {
        minSdk = 28
        targetSdk = 34
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    testOptions.managedDevices.localDevices {
        create("folioApi30") {
            device = "Pixel 2"
            apiLevel = 30
            systemImageSource = "aosp"
        }
    }
}

kotlin.compilerOptions.jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)

baselineProfile {
    useConnectedDevices = providers.gradleProperty("folioProfileConnected").isPresent
    if (!useConnectedDevices) managedDevices += "folioApi30"
}

dependencies {
    implementation("androidx.test.ext:junit:1.2.1")
    implementation("androidx.test:runner:1.6.2")
    implementation("androidx.test.uiautomator:uiautomator:2.4.0")
    implementation("androidx.benchmark:benchmark-macro-junit4:1.5.0")
    implementation("androidx.core:core-ktx:1.16.0")
}
