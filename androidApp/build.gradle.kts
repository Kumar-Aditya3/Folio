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
    compileSdk = 37

    defaultConfig {
        applicationId = "com.folio.reader"
        minSdk = 24
        targetSdk = 34
        versionCode = 171
        versionName = "1.2.117"
        vectorDrawables.useSupportLibrary = true
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // ONNX Runtime (embedding inference) and ML Kit (OCR + translate) each ship a
        // large .so per ABI, and Android packages every ABI's copy into one APK unless
        // told otherwise. Unfiltered, this doubled the APK: the two x86 ABIs exist only
        // for emulators, and no shipping phone needs them. Restrict to real device ABIs.
        // Both ONNX Runtime and ML Kit publish arm64-v8a and armeabi-v7a, so nothing is
        // lost for physical devices. Emulator users can add x86_64 back locally.
        ndk {
            abiFilters += setOf("arm64-v8a", "armeabi-v7a")
        }
    }

    // Split per ABI as well, so an install downloads only the slice it needs. Measured on the
    // debug variant: arm64-v8a ~85 MB, armeabi-v7a ~70 MB, universal ~117 MB — against ~229 MB
    // for the single unfiltered APK. requiresAbiFilters is inferred by AGP when the split names
    // match abiFilters, so the universal APK still contains both ABIs.
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a")
            isUniversalApk = true
        }
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

// FolioStaticField.android.kt reads Mirage's static shader host classes reflectively BY NAME
// (`Class.forName("io.androidpoet.mirage.HalftoneDotsKt")`), so R8 must not rename them;
// proguard-rules.pro `-keep`s the names. This guard turns a regression of that rule into a
// build failure rather than the silent runtime cost that shipped once already: with the class
// renamed, the lookup fails quietly and the static family falls back to the library wrapper,
// whose per-vsync clock re-runs a static full-screen shader every frame -- release only,
// debug fine.
val mirageMappingFile = layout.buildDirectory
    .file("outputs/mapping/release/mapping.txt").get().asFile
tasks.matching { it.name.startsWith("minify") && it.name.endsWith("WithR8") }.configureEach {
    doLast {
        if (!mirageMappingFile.exists()) return@doLast
        val mappingLines = mirageMappingFile.readLines()
        // Room, WorkManager and ML Kit instantiate these classes reflectively.
        // Older consumer rules kept the classes but omitted their constructors.
        val startupClasses = listOf(
            "androidx.work.impl.WorkDatabase_Impl",
            "androidx.work.ArrayCreatingInputMerger",
            "androidx.work.OverwritingInputMerger",
            "com.google.mlkit.common.internal.CommonComponentRegistrar",
            "com.google.mlkit.nl.translate.NaturalLanguageTranslateRegistrar",
            "com.google.mlkit.vision.common.internal.VisionCommonRegistrar",
            "com.google.mlkit.vision.text.internal.TextRegistrar",
        )
        // Unchanged constructors can be absent from mapping.txt; seeds.txt records
        // the explicitly kept constructor even when it needs no retrace mapping.
        val seedsFile = mirageMappingFile.resolveSibling("seeds.txt")
        val keptMembers = if (seedsFile.exists()) {
            seedsFile.useLines { lines -> lines.map { it.trim() }.toSet() }
        } else emptySet()
        val missingStartupClasses = startupClasses.filter { className ->
            "$className -> $className:" !in mappingLines ||
                "$className: ${className.substringAfterLast('.')}()" !in keptMembers
        }
        check(missingStartupClasses.isEmpty()) {
            "R8 removed or renamed startup classes or their no-argument constructors: " +
                missingStartupClasses.joinToString() +
                ". Check the RoomDatabase and ComponentRegistrar keep rules."
        }
        val renamed = mappingLines.mapNotNull { line ->
            Regex("""^io\.androidpoet\.mirage\.(\w+Kt) -> (\S+):$""")
                .find(line)
                // `R8$$REMOVED$$CLASS$$x` marks a class R8 stripped as dead code (an unused
                // Mirage family, e.g. GemSmokeKt) — that is not a rename, and the by-name
                // lookup never targets a class the app does not use. Only an actual rename
                // silently re-enables the per-vsync clock.
                ?.takeIf {
                    it.groupValues[2] != "io.androidpoet.mirage.${it.groupValues[1]}" &&
                        !it.groupValues[2].contains("REMOVED")
                }
                ?.groupValues?.get(1)
        }
        check(renamed.isEmpty()) {
            "R8 renamed Mirage shader host class(es): ${renamed.joinToString()}. The -keep " +
                "rules in proguard-rules.pro must preserve io.androidpoet.mirage.*Kt class " +
                "names -- FolioStaticField.android.kt looks them up reflectively by name, and " +
                "a rename silently re-enables the library's per-vsync clock in release."
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":shared"))
    // Conscrypt TLS provider — FolioApplication.onCreate installs it as the top
    // security provider. `implementation` in :shared does not expose it here, so the
    // app module declares it directly to reference org.conscrypt.Conscrypt.
    implementation(libs.conscrypt.android)

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
    testImplementation(kotlin("test"))
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
