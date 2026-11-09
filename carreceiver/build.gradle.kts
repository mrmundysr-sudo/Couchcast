plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.mundymorning.couchcast.carreceiver"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.mundymorning.couchcast.carreceiver"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0" + (System.getenv("GITHUB_RUN_NUMBER")?.let { "-build$it" } ?: "-local")
    }

    buildTypes { release { isMinifyEnabled = false } }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
    // The packaged games are already compressed art and audio.
    androidResources { noCompress += listOf("webp", "png", "jpg", "ogg", "mp3", "wav", "m4a") }
}

dependencies {
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.coil.compose)
    implementation(libs.nanohttpd)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.ui)
    implementation(libs.media3.session)
    implementation(libs.androidx.webkit)
    testImplementation(libs.junit)
    testImplementation(libs.okhttp)
}
