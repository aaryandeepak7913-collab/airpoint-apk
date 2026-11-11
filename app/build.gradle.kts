plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }
android {
    namespace = "com.aaryan.airpoint"
    compileSdk = 34
    defaultConfig { applicationId = "com.aaryan.airpoint"; minSdk = 28; targetSdk = 34; versionCode = 1; versionName = "1.0" }
    // Fixed signing key so new builds install over the old one
    signingConfigs { getByName("debug") { storeFile = file("../debug.keystore"); storePassword = "android"; keyAlias = "androiddebugkey"; keyPassword = "android" } }
    androidResources { noCompress += "task" } // MediaPipe reads the hand model straight from assets
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
}
dependencies {
    implementation("androidx.camera:camera-core:1.3.4")
    implementation("androidx.camera:camera-camera2:1.3.4")
    implementation("androidx.camera:camera-lifecycle:1.3.4")
    implementation("androidx.lifecycle:lifecycle-service:2.8.6")
    implementation("com.google.guava:guava:33.0.0-android")
    implementation("com.google.mediapipe:tasks-vision:0.10.14")
}
