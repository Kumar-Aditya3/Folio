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
