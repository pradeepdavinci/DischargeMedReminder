import java.net.URI

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.discharge.medreminder"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.discharge.medreminder"
        minSdk = 24
        targetSdk = 34
        versionCode = 3
        versionName = "2.1-foss"
    }
    buildTypes { release { isMinifyEnabled = false } }
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86", "x86_64")
            isUniversalApk = true
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    // Keep the OCR model uncompressed so it can be copied quickly on first launch
    androidResources { noCompress += "traineddata" }
    testOptions { unitTests.isReturnDefaultValues = true }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-ktx:1.9.0")
    implementation("androidx.exifinterface:exifinterface:1.3.7")
    // Tesseract OCR 5 for Android – Apache-2.0, multi-threaded variant
    implementation("cz.adaptech.tesseract4android:tesseract4android-openmp:4.9.0")
    testImplementation("junit:junit:4.13.2")
}

// The English OCR model (tessdata_best, Apache-2.0, ~15 MB) is downloaded once at build
// time instead of being committed to the repository.
val tessModel = file("src/main/assets/tessdata/eng.traineddata")
val downloadTessModel = tasks.register("downloadTessModel") {
    description = "Downloads the open-source Tesseract English model (tessdata_best)."
    outputs.file(tessModel)
    onlyIf { !tessModel.exists() || tessModel.length() < 1_000_000L }
    doLast {
        tessModel.parentFile.mkdirs()
        val tmp = File(tessModel.path + ".part")
        val conn = URI("https://raw.githubusercontent.com/tesseract-ocr/tessdata_best/main/eng.traineddata")
            .toURL().openConnection()
        conn.setRequestProperty("User-Agent", "DischargeMedReminder-Build")
        conn.getInputStream().use { input -> tmp.outputStream().use { input.copyTo(it) } }
        if (!tmp.renameTo(tessModel)) throw GradleException("Could not save $tessModel")
    }
}
tasks.named("preBuild") { dependsOn(downloadTessModel) }
