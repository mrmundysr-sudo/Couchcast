plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.mundymorning.couchcast"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.mundymorning.couchcast"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
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

// The Roku receiver channel (/receiver) is zipped and bundled as an asset. The app uploads this ZIP to the Roku.
val receiverZip = tasks.register<Zip>("packageRokuReceiver") {
    from(rootProject.file("receiver"))
    archiveFileName.set("couchcast-receiver.zip")
    destinationDirectory.set(layout.buildDirectory.dir("generated/receiverAssets"))
}
android.sourceSets.getByName("main").assets.srcDir(layout.buildDirectory.dir("generated/receiverAssets"))
tasks.configureEach {
    if (name.startsWith("merge") && name.endsWith("Assets")) dependsOn(receiverZip)
}

dependencies {
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.okhttp)
    implementation(libs.coil.compose)
    testImplementation(libs.junit)
    testImplementation(libs.okhttp.mockwebserver)
}
