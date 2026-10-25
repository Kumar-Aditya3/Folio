# Folio ProGuard Rules

# kotlinx.serialization: runtime metadata plus the generated serializers for the
# models that travel through sync payloads, backups and settings storage.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** { kotlinx.serialization.KSerializer serializer(...); }
-keep,includedescriptorclasses class com.folio.reader.**$$serializer { *; }
-keepclassmembers class com.folio.reader.** { *** Companion; }
-keepclasseswithmembers class com.folio.reader.** { kotlinx.serialization.KSerializer serializer(...); }

# App code is small; keep it whole and let R8 shrink the third-party world.
-keep class com.folio.reader.** { *; }

# The SQLite JDBC bridge is loaded reflectively (Class.forName) in Database.
-keep class org.sqldroid.** { *; }
-keep class org.sqlite.** { *; }

# WorkManager 2.9.1 brings Room 2.5.0, whose consumer rule keeps database
# classes but omits their reflectively invoked no-argument constructors.
# New R8 removes WorkDatabase_Impl.<init>(), crashing InitializationProvider
# before Application.onCreate. Keep the constructor explicitly (Room's newer rule).
-keep class * extends androidx.room.RoomDatabase {
    <init>();
}

# Firebase's older component discovery rule also keeps only the class. ML Kit
# discovers these registrars from manifest metadata, then invokes <init>() via
# reflection. Without it, OCR and translation lose their registered components.
-keep class * implements com.google.firebase.components.ComponentRegistrar {
    <init>();
}

# WorkManager also creates its input mergers by class name when work executes.
# Its older consumer rule has the same class-only constructor omission.
-keep class * extends androidx.work.InputMerger {
    <init>();
}

# Coroutines / datetime metadata used by the serialization runtime.
-keep class kotlinx.coroutines.** { *; }
-keep class kotlinx.datetime.** { *; }

# xerial sqlite-jdbc references desktop-only JDBC symbols that Android's
# java.sql stub lacks; the code paths are never hit through sqldroid.
-dontwarn java.sql.JDBCType
-dontwarn org.slf4j.impl.StaticLoggerBinder

# --- Mihon / Injekt extension runtime ---
# Injekt resolves types via object : TypeReference<T>() {} whose actual type is read
# from the generic superclass Signature at runtime; R8 must not strip it.
-keepattributes Signature, EnclosingMethod, *Annotation*, InnerClasses
-keep class uy.kohesive.injekt.** { *; }
-keep class * extends uy.kohesive.injekt.api.TypeReference { *; }
# Loaded extension APKs implement / subclass these; keep them un-obfuscated.
-keep class eu.kanade.tachiyomi.** { *; }
-keep class tachiyomi.** { *; }
-keep class mihon.** { *; }
# Extension APKs are compiled against the host's runtime libraries and link by exact
# class names and method descriptors (e.g. Filter$Separator's synthetic constructor
# takes kotlin.jvm.internal.DefaultConstructorMarker). Renaming or shrinking ANY of
# these breaks extension loading and browsing at runtime, so keep them whole.
-keep class kotlin.** { *; }
-keep class kotlinx.** { *; }
-keep class okhttp3.** { *; }
-keep class okio.** { *; }
-keep class org.jsoup.** { *; }
-keep class app.cash.quickjs.** { *; }
# Conscrypt is installed as the top JCE security provider at startup and resolves
# much of its native (BoringSSL/JNI) surface reflectively; shrinking it breaks the
# provider registration and TLS falls back to the platform stack we're replacing.
-keep class org.conscrypt.** { *; }
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
# okhttp3 ships GraalVM native-image hooks and jsoup references jspecify annotations;
# neither exists on Android and both are unreachable at runtime.
-dontwarn com.oracle.svm.core.annotate.**
-dontwarn org.graalvm.nativeimage.**
-dontwarn java.lang.Module
-dontwarn org.jspecify.annotations.**

# okhttp 5's CompressionInterceptor decodes zstd/br responses (many manga sources
# send them). libzstd-kmp.so resolves these classes through JNI FindClass, which R8
# cannot see, so shrinking them turned the first zstd response into an uncatchable
# native abort:
#   Abort message: 'No pending exception expected:
#   java.lang.ClassNotFoundException: com.squareup.zstd.ZstdCompressor
#     at com.squareup.zstd.JniZstdKt.createJniZstd()
# It only reproduced in release builds (R8 on) and only once a source was browsed —
# i.e. immediately at launch for anyone who already had extensions installed.
-keep class com.squareup.zstd.** { *; }
-keepclasseswithmembernames,includedescriptorclasses class com.squareup.zstd.** {
    native <methods>;
}
-keep class org.brotli.** { *; }

# --- Sentry crash reporting (§8.4 FOLIO_IMPLEMENTATION_SPEC) ──────────────
# Sentry uses reflection + native libraries for crash capture; must be kept.
-dontnote io.sentry.*
-keep class io.sentry.** { *; }
-keep class kotlin.reflect.** { *; }
# Native libs for crash tracing (minidump, backtrace)
-keep class org.hildan.fordilla.** { *; }
-dontwarn org.slf4j.**

# --- ONNX Runtime (semantic search, ML_PLAN Phase 4) ──────────────────────
# Same shape as the zstd abort above: the native layer instantiates these Java
# classes by name, so R8 cannot see the reference and strips them. Inference then
# aborts on the first embedding:
#   Pending exception java.lang.ClassNotFoundException: ai.onnxruntime.TensorInfo
#   Fatal signal 6 (SIGABRT) in tid (DefaultDispatch)
#     at Java_ai_onnxruntime_OrtSession_run
# It reproduces only with R8 on and only once an embedding actually runs, so a
# debug build and the whole desktop test suite pass without it.
-keep class ai.onnxruntime.** { *; }
-keepclasseswithmembernames,includedescriptorclasses class ai.onnxruntime.** {
    native <methods>;
}
-dontwarn ai.onnxruntime.**

# Mirage's static shader family is hosted by Folio with the library's vsync clock
# switched off (see FolioStaticField.android.kt). `halftoneDots` — and `paperTexture`,
# before that family was removed — do not forward a `speed` to `mirageEffect`, so the
# wrapper ran a per-frame clock to re-render a bit-identical full-screen image. The fix
# re-hosts the family via the public `mirageEffect(speed = 0f)` using its own shader
# source, which is a private top-level `val SHADER` in the generated `*Kt` class and is
# therefore read reflectively BY NAME (`Class.forName`).
#
# The rule must be `-keep`, not `-keepclassmembers`: the members-only form preserves the
# field but lets R8 rename the class, and a renamed class makes Class.forName throw, which
# the reflective lookup swallows before falling back to the library wrapper -- i.e. the
# per-frame clock returns in release while debug is fine. Degradation, never a crash, and
# that exact failure shipped once: the release mapping showed
# `io.androidpoet.mirage.PaperTextureKt -> M3.G`. The Gradle guard in androidApp/
# build.gradle.kts fails the build if these classes are ever renamed again.
-keep class io.androidpoet.mirage.HalftoneDotsKt {
    java.lang.String SHADER;
}

