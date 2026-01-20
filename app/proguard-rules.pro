# Add project specific ProGuard rules here.
# By default, the flags in this file are appended to flags specified
# in /files/proguard-android-optimize.txt
# --- OkHttp Rules ---
# OkHttp использует Okio и рефлексию, нужно сохранить эти классы
-keepattributes Signature
-keepattributes *Annotation*
-keep class okhttp3.** { *; }
-keep interface okhttp3.** { *; }
-dontwarn okhttp3.**
-dontwarn okio.**

# --- Kotlin Coroutines ---
# Чтобы не сломалась многопоточность
-keep class kotlinx.coroutines.internal.MainDispatcherFactory { *; }
-keep class kotlinx.coroutines.android.AndroidExceptionPreHandler { *; }
-keep class kotlinx.coroutines.android.AndroidDispatcherFactory { *; }

# --- Jetpack Compose ---
# Стандартные правила для Compose обычно встроены в библиотеку,
# но эти правила гарантируют совместимость
-keep class androidx.compose.** { *; }

# --- Android Entry Points ---
# Сохраняем Activity и Application, иначе Android не сможет запустить приложение
-keep class com.dnstohosts.app.** { *; }
