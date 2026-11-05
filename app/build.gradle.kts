plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.negocio.editorvideos"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.negocio.editorvideos"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }

    // Llave fija incluida en el proyecto: así cada APK nuevo se instala
    // encima del anterior sin tener que desinstalar.
    signingConfigs {
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

dependencies {
    val media3 = "1.5.1"
    implementation("androidx.media3:media3-transformer:$media3")
    implementation("androidx.media3:media3-effect:$media3")
    implementation("androidx.media3:media3-common:$media3")

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("com.google.android.material:material:1.12.0")
}
