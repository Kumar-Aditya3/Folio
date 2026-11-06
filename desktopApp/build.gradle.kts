import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.compose.multiplatform)
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

dependencies {
    // compose.desktop.currentOs pulls the platform skiko natives
    // (skiko-windows-x64 runtime) that the catalog alias misses.
    implementation(project(":shared"))
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(compose.foundation)
    implementation(compose.runtime)
    implementation(libs.kotlinx.coroutines.core)
    // Provides Dispatchers.Main (Swing/EDT) on desktop JVM
    implementation(libs.kotlinx.coroutines.swing)
    // SyncState exposes kotlinx.datetime.Instant to the desktop UI.
    implementation(libs.kotlinx.datetime)
    implementation(libs.slf4j.simple)
    // jcefmaven owns native installation and exposes the supported CefAppBuilder.
    implementation(libs.jcefmaven)
    runtimeOnly(libs.jcef.natives.windows.amd64)

    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlin.test.junit5)
}

compose.desktop {
    application {
        mainClass = "com.folio.reader.MainKt"
        // Direct3D can spin in Skiko's first flush before the window is visible.
        // OpenGL keeps hardware acceleration and passes the native launcher smoke test.
        jvmArgs(
            "--enable-native-access=ALL-UNNAMED",
            "-Dskiko.renderApi=OPENGL",
            // Bound retained textures from library/glass screens after entering
            // the native reader. Chromium owns the book's own rendering budget.
            "-Dskiko.gpu.resourceCacheLimit=64M",
        )
        nativeDistributions {
            targetFormats(
                org.jetbrains.compose.desktop.application.dsl.TargetFormat.Msi,
                org.jetbrains.compose.desktop.application.dsl.TargetFormat.Exe
            )
            // The packaged launcher is a GUI application, not a console wrapper.
            windows {
                console = false
                iconFile.set(rootProject.file("icon/Folio.ico"))
                shortcut = true
                menu = true
            }
            // SQLite JDBC and Compose load some classes reflectively. Ship the
            // complete runtime to avoid a pruned jlink image failing before the
            // JVM can report its missing dependency on Windows.
            includeAllModules = true
            packageName = "Folio"
            packageVersion = "1.0.38"
            windows {
                fileAssociation("application/epub+zip", "epub", "EPUB eBook")
                fileAssociation("application/pdf", "pdf", "PDF document")
                fileAssociation("text/plain", "txt", "Text document")
                fileAssociation("text/html", "html", "HTML document")
                fileAssociation("text/html", "htm", "HTML document")
                fileAssociation(
                    "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                    "docx",
                    "Word document"
                )
                fileAssociation(
                    "application/vnd.oasis.opendocument.text",
                    "odt",
                    "OpenDocument text"
                )
            }
        }
    }
}

tasks.withType<Test> {
    useJUnitPlatform()
}

tasks.processResources {
    from(rootProject.file("icon/Folio.png")) {
        rename { "folio-icon.png" }
    }
}
