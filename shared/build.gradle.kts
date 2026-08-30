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
            }
        }

        getByName("androidMain") {
            dependsOn(getByName("commonJvm"))
            dependsOn(getByName("composeUi"))
            dependencies {
                implementation(libs.androidx.lifecycle.runtime)
                implementation(libs.androidx.core.ktx)
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
                runtimeOnly(libs.jcef.natives.windows.amd64)
                implementation(libs.slf4j.api)
                implementation(libs.slf4j.simple)
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
}

tasks.withType<Test> {
    useJUnitPlatform()
}
