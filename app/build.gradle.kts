import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    kotlin("android")
    kotlin("plugin.compose")
    kotlin("plugin.serialization")
}

// Fixed signing key, so every build installs over the previous one. CI provides the
// keystore through the GOLDORB_KEYSTORE_* secrets; without them the default debug key is used.
val keystorePassword = System.getenv("GOLDORB_KEYSTORE_PASSWORD")?.takeIf { it.isNotBlank() }
val keystoreFile = System.getenv("GOLDORB_KEYSTORE_FILE")?.let(::file)
val hasFixedKey = keystorePassword != null && keystoreFile?.exists() == true

android {
    namespace = "com.goldorb"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.goldorb"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }

    signingConfigs {
        if (hasFixedKey) {
            create("fixed") {
                storeFile = keystoreFile
                storeType = "pkcs12"
                storePassword = keystorePassword
                keyAlias = System.getenv("GOLDORB_KEY_ALIAS") ?: "goldorb"
                keyPassword = keystorePassword
            }
        }
    }

    buildTypes {
        val signing = signingConfigs.getByName(if (hasFixedKey) "fixed" else "debug")
        debug { signingConfig = signing }
        release {
            isMinifyEnabled = false
            signingConfig = signing
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures { compose = true }

    packaging {
        resources.excludes += setOf("META-INF/{AL2.0,LGPL2.1}", "META-INF/versions/9/previous-compilation-data.bin")
    }
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    implementation(project(":core"))

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.datastore:datastore-preferences:1.1.1")
    implementation("androidx.browser:browser:1.8.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
}
