import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Server URL + ingest token are kept OUT of source control (secrets.properties is gitignored).
// Copy secrets.properties.example to secrets.properties and fill in your values.
val secrets = Properties().apply {
    val f = rootProject.file("secrets.properties")
    if (f.exists()) load(f.inputStream())
}

android {
    namespace = "com.lifetrace"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.lifetrace"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"

        // Pulled from secrets.properties at build time (never committed).
        buildConfigField("String", "BASE_URL", "\"${secrets.getProperty("BASE_URL", "")}\"")
        buildConfigField("String", "INGEST_TOKEN", "\"${secrets.getProperty("INGEST_TOKEN", "")}\"")
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.10"
    }

    kotlinOptions {
        jvmTarget = "1.8"
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.02.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.activity:activity-compose:1.8.2")
    implementation("androidx.core:core-ktx:1.12.0")
}
