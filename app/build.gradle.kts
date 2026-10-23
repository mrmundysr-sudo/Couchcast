import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

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
        versionName = "0.1.0" + (System.getenv("GITHUB_RUN_NUMBER")?.let { "-build$it" } ?: "-local")
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
// A dedicated task declares its input and output directories so merge, lint and packaging all depend on it correctly.
abstract class PackageRokuReceiver : DefaultTask() {
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val receiverDir: DirectoryProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun zip() {
        val src = receiverDir.get().asFile
        val out = outputDir.get().asFile.also { it.mkdirs() }.resolve("couchcast-receiver.zip")
        val files = src.walkTopDown().filter { it.isFile }.sortedBy { it.relativeTo(src).invariantSeparatorsPath }.toList()
        ZipOutputStream(out.outputStream().buffered()).use { z ->
            for (f in files) {
                z.putNextEntry(ZipEntry(f.relativeTo(src).invariantSeparatorsPath).apply { time = 315532800000L })
                f.inputStream().use { it.copyTo(z) }
                z.closeEntry()
            }
        }
    }
}

val receiverZip = tasks.register<PackageRokuReceiver>("packageRokuReceiver") {
    receiverDir.set(rootProject.layout.projectDirectory.dir("receiver"))
}
androidComponents {
    onVariants { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(receiverZip, PackageRokuReceiver::outputDir)
    }
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
    implementation(libs.nanohttpd)
    testImplementation(libs.junit)
    testImplementation(libs.okhttp.mockwebserver)
}
