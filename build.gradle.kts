// build.gradle.kts (Root)
plugins {
    // Android Gradle Plugin 9.0.0
    id("com.android.application") version "9.0.0" apply false
    
    // Kotlin 2.3.10
    id("org.jetbrains.kotlin.android") version "2.3.10" apply false
    
    // !!! ВАЖНО: Новый плагин компилятора Compose (нужен для Kotlin 2.0+)
    id("org.jetbrains.kotlin.plugin.compose") version "2.3.10" apply false
}
