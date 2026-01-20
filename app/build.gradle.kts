import java.io.FileInputStream
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.dnstohosts.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.dnstohosts.app"
        minSdk = 26
        targetSdk = 34
        
        // Получаем версию от GitHub Actions
        val cmdVersionName = project.findProperty("versionName") as? String
        val cmdVersionCode = project.findProperty("versionCode") as? String

        versionCode = cmdVersionCode?.toIntOrNull() ?: 1
        versionName = cmdVersionName ?: "1.0.0"

        vectorDrawables {
            useSupportLibrary = true
        }
    }

    signingConfigs {
        create("release") {
            val keystoreFile = rootProject.file("release.keystore")
            if (keystoreFile.exists()) {
                storeFile = keystoreFile
                storePassword = System.getenv("KEY_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("ALIAS_PASSWORD")
            } else {
                storeFile = rootProject.file("debug.keystore")
                storePassword = "android"
                keyAlias = "androiddebugkey"
                keyPassword = "android"
            }
        }
    }

    buildTypes {
        release {
            // --- ОПТИМИЗАЦИЯ ВКЛЮЧЕНА ЗДЕСЬ ---
            isMinifyEnabled = true       // Включает R8 (сжатие кода и обфускация)
            isShrinkResources = true     // Удаляет неиспользуемые ресурсы
            
            // Используем rules для агрессивной оптимизации
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            
            signingConfig = signingConfigs.getByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    kotlinOptions {
        jvmTarget = "1.8"
    }
    buildFeatures {
        compose = true
    }
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.1"
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.10.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.6.1")
    implementation("androidx.activity:activity-compose:1.7.0")
    implementation(platform("androidx.compose:compose-bom:2023.08.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("com.google.android.material:material:1.11.0")
    implementation("com.squareup.okhttp3:okhttp:4.11.0")
}
