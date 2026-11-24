plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.handarm"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.handarm"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }
    buildTypes { release { isMinifyEnabled = false } }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    androidResources { noCompress += "task" }   // MediaPipe model must stay uncompressed
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    val camerax = "1.3.4"
    implementation("androidx.camera:camera-core:$camerax")
    implementation("androidx.camera:camera-camera2:$camerax")
    implementation("androidx.camera:camera-lifecycle:$camerax")
    implementation("androidx.camera:camera-view:$camerax")

    implementation("com.google.mediapipe:tasks-vision:0.10.14")

    testImplementation("junit:junit:4.13.2")
}

// Downloads the MediaPipe hand-landmark model into assets on first build (needs internet once).
// Or download it yourself and put it at app/src/main/assets/hand_landmarker.task
val modelFile = layout.projectDirectory.file("src/main/assets/hand_landmarker.task").asFile
val downloadHandModel by tasks.registering {
    onlyIf { !modelFile.exists() || modelFile.length() < 1_000_000L }
    doLast {
        modelFile.parentFile.mkdirs()
        val url = "https://storage.googleapis.com/mediapipe-models/hand_landmarker/hand_landmarker/float16/latest/hand_landmarker.task"
        java.net.URL(url).openStream().use { input ->
            modelFile.outputStream().use { out -> input.copyTo(out) }
        }
    }
}
tasks.named("preBuild") { dependsOn(downloadHandModel) }
