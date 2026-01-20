# --- Оптимизация ---
# Разрешаем R8 менять имена методов и полей для максимального сжатия
-repackageclasses ''
-allowaccessmodification

# --- OkHttp ---
# OkHttp 4+ на Kotlin хорошо дружит с R8, оставляем только подавление варнингов,
# которые могут возникнуть из-за старых зависимостей Java
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn javax.annotation.**

# --- Jetpack Compose ---
# НЕ ПИШЕМ сюда -keep class androidx.compose.**
# Compose сам знает, что ему нужно оставить.

# --- Общие правила ---
# Сохраняем атрибуты, полезные для отладки крашей (можно убрать для еще большего сжатия, но не советую)
-keepattributes SourceFile,LineNumberTable
-keepattributes *Annotation*
-keepattributes Signature
-keepattributes EnclosingMethod
-keepattributes InnerClasses

# Если вдруг приложение упадет с ошибкой "Class not found" для MainActivity (редко, но бывает)
-keep class com.dnstohosts.app.MainActivity { <init>(); }
