import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.compose.compiler)
}

// Release signing credentials live in /keystore.properties (not committed to VCS).
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "com.folio.reader"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.folio.reader"
        minSdk = 24
        targetSdk = 34
        versionCode = 62
        versionName = "1.2.9"
        vectorDrawables.useSupportLibrary = true
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (keystoreProps.isNotEmpty()) {
            create("release") {
                storeFile = file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            // Instrumented runs install the debug app over the release-signed install;
            // matching signatures lets connectedAndroidTest keep the user's data intact.
            if (keystoreProps.isNotEmpty()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (keystoreProps.isNotEmpty()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources.excludes += "META-INF/*.kotlin_module"
        jniLibs.excludes += setOf(
            "org/sqlite/native/Linux/**",
            "org/sqlite/native/Mac/**",
            "org/sqlite/native/Windows/**",
            "org/sqlite/native/FreeBSD/**",
            "org/sqlite/native/OpenBSD/**"
        )
    }

    lint {
        abortOnError = false
        warningsAsErrors = false
        // AGP's bundled lint crashes on this machine's JDK 25 (AndroidLintWorkAction
        // throws "25.0.1"); release-lint gating is skipped so assembleRelease works.
        checkReleaseBuilds = false
    }
}

tasks.configureEach {
    if (name.contains("lintVital", ignoreCase = true)) enabled = false
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":shared"))

    // JDBC bridge over android.database.sqlite — provides the SQLite JDBC driver on Android
    // (xerial sqlite-jdbc only ships desktop natives).
    implementation("org.sqldroid:sqldroid:1.1.0-rc1")

    // kotlinx.datetime is used by SyncState (Instant) exposed from shared module
    implementation(libs.kotlinx.datetime)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime)
    implementation(libs.androidx.lifecycle.viewmodel)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.activity.compose)
    implementation("androidx.documentfile:documentfile:1.0.1")
    // Required when Android code constructs shared SyncState values containing Instant fields.
    implementation(libs.kotlinx.datetime)

    implementation(libs.compose.multiplatform.runtime)
    implementation(libs.compose.multiplatform.ui)
    implementation(libs.compose.multiplatform.foundation)
    implementation(libs.compose.multiplatform.material3)
    implementation(libs.androidx.navigation.compose)

    implementation(libs.coil.compose)

    // ── TEST INFRASTRUCTURE (§8.1 FOLIO_IMPLEMENTATION_SPEC) ────────────────
    // Pin exact versions to match Compose 1.7.6 baseline; no ranges.
    androidTestImplementation("androidx.compose.ui:ui-test-junit4:1.7.6")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
    androidTestImplementation("androidx.test.uiautomator:uiautomator:2.2.0")
    debugImplementation("androidx.compose.ui:ui-test-manifest:1.7.6")

    // ── CRASH REPORTING (§8.4 FOLIO_IMPLEMENTATION_SPEC) ────────────────────
    // Opt-in crash reporting with privacy notice; reports include stack trace + build version.
    implementation("io.sentry:sentry-android:7.3.0")

    // ── MANGA UPDATE CHECKS (§11.3) ─────────────────────────────────────────
    // 2.9.1 is the newest line that still builds against compileSdk 34 (2.10 needs 35).
    implementation("androidx.work:work-runtime-ktx:2.9.1")
}
