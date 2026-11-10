import java.util.Properties

plugins {
    id("com.android.application")
}

val localProperties = Properties().apply {
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use(::load)
}

android {
    namespace = "com.example.svthreadleak"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.example.svthreadleak"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
        // Set MAPS_API_KEY in local.properties (Maps SDK for Android enabled).
        manifestPlaceholders["mapsApiKey"] = localProperties.getProperty("MAPS_API_KEY", "")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation("com.google.android.gms:play-services-maps:20.0.0")
}
