plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Número de versión: en GitHub Actions sube solo con cada compilación.
val numero = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()
val claveArchivo = file("biblioteca.keystore")

android {
    namespace = "ar.biblioteca.ia"
    compileSdk = 35

    defaultConfig {
        applicationId = "ar.biblioteca.ia"
        minSdk = 24
        targetSdk = 34
        versionCode = numero
        versionName = "1.0.$numero"
    }

    signingConfigs {
        if (claveArchivo.exists()) {
            create("estable") {
                storeFile = claveArchivo
                storePassword = System.getenv("KEYSTORE_PASSWORD") ?: ""
                keyAlias = "biblioteca"
                keyPassword = System.getenv("KEYSTORE_PASSWORD") ?: ""
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = if (claveArchivo.exists()) signingConfigs.getByName("estable")
                            else signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    packaging {
        resources.excludes += setOf("META-INF/DEPENDENCIES", "META-INF/LICENSE*", "META-INF/NOTICE*")
    }
}

dependencies {
    implementation("androidx.webkit:webkit:1.12.1")
    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation("com.tom-roush:pdfbox-android:2.0.27.0")
}
