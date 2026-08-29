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
                implementation(libs.kotlinx.datetime)
            }
        }

        // Shared JVM code (Android + Desktop are both JVM targets).
        create("commonJvm") {
            dependsOn(getByName("commonMain"))
            dependencies {
                implementation(libs.sqlite.jdbc)
                implementation(libs.kxml2)
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
                api(libs.androidx.lifecycle.viewmodel)
            }
        }

        getByName("androidMain") {
            dependsOn(getByName("commonJvm"))
            dependsOn(getByName("composeUi"))
            dependencies {
                implementation(libs.androidx.lifecycle.runtime)
                implementation(libs.androidx.datastore.preferences)
                implementation(libs.androidx.core.ktx)
                implementation(libs.coil.compose)
            }
        }

        getByName("desktopMain") {
            dependsOn(getByName("commonJvm"))
            dependsOn(getByName("composeUi"))
            dependencies {
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
