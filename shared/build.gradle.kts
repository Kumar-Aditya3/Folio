import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.android.library)
}

repositories {
    google()
    mavenCentral()
    // Mihon's injekt fork (extension runtime DI expected by loaded extension APKs).
    maven(url = "https://jitpack.io")
}

kotlin {
    androidTarget {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    jvm("desktop") {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    sourceSets {
        getByName("commonMain") {
            dependencies {
                implementation(libs.kotlinx.coroutines.core)
                implementation(libs.kotlinx.serialization.json)
                implementation(libs.kotlinx.serialization.protobuf)
                implementation(libs.kotlinx.datetime)
            }
        }

        // Shared JVM code (Android + Desktop are both JVM targets).
        create("commonJvm") {
            dependsOn(getByName("commonMain"))
            dependencies {
                implementation(libs.sqlite.jdbc)
                implementation(libs.jsoup)
                // API only: Android ships org.xmlpull.v1 in the framework; desktop
                // packages the implementation via desktopMain below.
                compileOnly(libs.kxml2)
            }
        }

        // Compose UI shared between Android and Desktop.
        create("composeUi") {
            dependsOn(getByName("commonJvm"))
            dependencies {
                api(libs.compose.multiplatform.runtime)
                api(libs.compose.multiplatform.ui)
                api(libs.compose.multiplatform.foundation)
                api(libs.compose.multiplatform.material3)
                api(libs.compose.multiplatform.material.icons.extended)
                api(libs.androidx.lifecycle.viewmodel)
                // §16 liquid glass. implementation, not api: the Haze types must stay
                // invisible to consumers so the blur seam cannot scatter (the whole
                // library is imported by exactly one file, Glass.kt).
                implementation(libs.haze)
            }
        }

        getByName("androidMain") {
            dependsOn(getByName("commonJvm"))
            dependsOn(getByName("composeUi"))
            dependencies {
                implementation(libs.androidx.lifecycle.runtime)
                implementation(libs.androidx.core.ktx)
                // FolioBackHandler's Android actual — system-back interception
                // for the shared selection screens.
                implementation(libs.androidx.activity.compose)
                implementation(libs.coil.compose)
                // Mihon manga backend: extension runtime + source engine. okhttp 5.5.0
                // matches what Mihon ships; extensions are compiled against it.
                implementation(libs.okhttp)
                implementation(libs.okhttp.brotli)
                implementation(libs.okhttp.zstd)
                runtimeOnly(libs.quickjs)
                implementation(libs.jsoup)
                implementation(libs.rxjava1)
                implementation(libs.injekt)
                implementation(libs.androidx.preference)
                implementation(libs.androidx.webkit)
                implementation(libs.kotlinx.coroutines.core)
                // On-device embedding inference. The Android artifact carries its own
                // native libs per ABI and cannot be resolved on desktop, which is why
                // openOnnxModel() is expect/actual rather than living in commonJvm.
                implementation(libs.onnxruntime.android)
                // Phase 6 (OCR + translation). Same shape of problem as ONNX above, and the
                // same answer: Android-only AARs, so `onDeviceTextTools()` is expect/actual
                // and desktop's actual is a documented no-op. Text recognition is one model
                // per script; Latin is the default and the other four are opt-in, which is
                // why they are declared here rather than conditionally.
                implementation(libs.mlkit.text.recognition)
                implementation(libs.mlkit.text.recognition.chinese)
                implementation(libs.mlkit.text.recognition.devanagari)
                implementation(libs.mlkit.text.recognition.japanese)
                implementation(libs.mlkit.text.recognition.korean)
                implementation(libs.mlkit.translate)
            }
        }

        getByName("desktopMain") {
            dependsOn(getByName("commonJvm"))
            dependsOn(getByName("composeUi"))
            dependencies {
                // Desktop has no platform XmlPullParser; Android ships its own
                // (bundling kxml2 there collided with framework classes in R8).
                implementation(libs.kxml2)
                implementation(libs.jcefmaven)
                implementation(libs.pdfbox)
                runtimeOnly(libs.jcef.natives.windows.amd64)
                implementation(libs.slf4j.api)
                implementation(libs.slf4j.simple)
                // Desktop ONNX Runtime: a different artifact from onnxruntime-android,
                // bundling its own win/linux/osx natives.
                implementation(libs.onnxruntime)
            }
        }

        getByName("commonTest") {
            dependencies {
                implementation(libs.kotlin.test)
                implementation(libs.kotlinx.coroutines.test)
            }
        }

        getByName("desktopTest") {
            dependencies {
                implementation(libs.kotlin.test.junit5)
                implementation(libs.junit.jupiter)
                implementation(libs.kotlinx.coroutines.test)
                runtimeOnly(libs.skiko.awt.runtime.windows.x64)
                // Reference tokenizer, test-only. Used to prove the hand-rolled WordPiece
                // implementation matches the Rust reference, and to tokenize the
                // SentencePiece multilingual model that the Phase 0b comparison needs.
                implementation(libs.djl.tokenizers)
            }
        }
    }
}

android {
    namespace = "com.folio.reader.shared"
    compileSdk = 34

    defaultConfig {
        minSdk = 24
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        // AGP's bundled lint crashes on this machine's JDK 25 ("25.0.1" thrown
        // from AndroidLintWorkAction, plus UAST MessageBus disposal crashes).
        checkReleaseBuilds = false
        abortOnError = false
    }
}

// checkReleaseBuilds=false alone still leaves lintVitalAnalyzeRelease in the
// app's release graph for this library module; disable the whole family.
tasks.configureEach {
    if (name.contains("lintVital", ignoreCase = true)) enabled = false
}

// Phase 0b scoping knobs, forwarded from the Gradle command line:
//   -Dfolio.bench.books=N     limit the corpus
//   -Dfolio.bench.e5=false    skip the 113 MB multilingual row
//
// Gradle does **not** pass `-D` through to test JVMs on its own, so before this the benchmark's
// scoping properties were read by `System.getProperty` inside the test and always came back
// null — every run was the full ~36-minute one no matter what was asked for. Read from
// `startParameter` rather than `System.getProperty` so the values do not leak into the shared
// Gradle daemon and affect later builds.
val benchSystemProperties: Map<String, String> =
    gradle.startParameter.systemPropertiesArgs.filterKeys { it.startsWith("folio.bench.") }

tasks.withType<Test> {
    useJUnitPlatform()

    // Gradle's default test-worker heap is 512 MB, and the Phase 0b benchmark needs far more:
    // it holds the whole corpus in memory (every chapter's text, plus a normalized copy for
    // gold-label matching) alongside two models' vectors and two live ONNX sessions. At the
    // default it dies ~22 minutes in with
    //   ai.onnxruntime.OrtException: Error code - ORT_FAIL - message: bad allocation
    // raised from OrtSession.run — which reads like a model or inference bug and is neither.
    maxHeapSize = "3g"

    benchSystemProperties.forEach { (key, value) -> systemProperty(key, value) }

    // `RetrievalQualityBenchmark` is the Phase 0b measurement: it embeds the whole 22-book
    // corpus twice and takes ~36 minutes, so it must never be swept up by a routine
    // `:shared:desktopTest` run. It self-gates on model fixtures being present, which they are
    // on a dev machine — so the gate does not protect an ordinary test run. Exclude it unless
    // explicitly asked for with `-PfolioBench`.
    //
    // `-PfolioBench` (rather than a `--tests` allow-list) is deliberate: a `--tests` filter that
    // matches nothing reports BUILD SUCCESSFUL with no XML written, which has already produced a
    // false green in this repo.
    if (!project.hasProperty("folioBench")) {
        exclude("**/RetrievalQualityBenchmark*")
        // Same reasoning: heavy, fixture-gated retrieval-quality probes that embed a whole book and
        // must not run in a routine `:shared:desktopTest`. They self-skip when fixtures are absent,
        // but fixtures are present on a dev machine, so the gate alone would not stop them.
        exclude("**/MalazanQualitativeProbe*")
        exclude("**/RerankProbe*")
    }
}
