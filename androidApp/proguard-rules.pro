# Folio ProGuard Rules

# Keep Kotlin serialization
-keep class kotlinx.serialization.** { *; }
-keep class com.folio.reader.** { *; }

# Keep SQLDelight
-keep class app.cash.sqldelight.** { *; }
-keep class com.folio.reader.database.** { *; }

# Keep Koin
-keep class org.koin.** { *; }

# Keep Ktor
-keep class io.ktor.** { *; }

# Keep Coil
-keep class io.coil-kt.** { *; }

# Keep Firebase
-keep class com.google.firebase.** { *; }

# Keep Okio
-keep class okio.** { *; }

# Keep Coroutines
-keep class kotlinx.coroutines.** { *; }

# Keep DateTime
-keep class kotlinx.datetime.** { *; }