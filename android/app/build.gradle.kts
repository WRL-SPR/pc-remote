import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("app.cash.paparazzi")
}

// Release signing key lives outside the repository (default: ~/.pcremote/keystore.properties,
// or the path in the PCREMOTE_KEYSTORE_PROPERTIES environment variable). Without it, release
// builds are left unsigned; debug builds always work.
val keystoreProperties = Properties().apply {
    val path = System.getenv("PCREMOTE_KEYSTORE_PROPERTIES")
        ?: "${System.getProperty("user.home")}/.pcremote/keystore.properties"
    val file = file(path)
    if (file.exists()) file.inputStream().use { load(it) }
}

android {
    namespace = "dev.pcremote"
    compileSdk = 34

    defaultConfig {
        applicationId = "dev.pcremote"
        minSdk = 26
        targetSdk = 34
        versionCode = 7
        versionName = "1.5.0"
    }

    signingConfigs {
        if (keystoreProperties.containsKey("storeFile")) {
            create("release") {
                storeFile = file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.09.00"))
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.5")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.5")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
}
