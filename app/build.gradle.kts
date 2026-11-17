plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}
android {
    namespace = "ir.elambar.mobl"
    compileSdk = 35
    defaultConfig {
        applicationId = "ir.elambar.mobl"
        minSdk = 24
        targetSdk = 35
        versionCode = 25
        versionName = "1.0.24"
        val turnUrl = (System.getenv("ELAM_TURN_URL") ?: "").replace("\\", "\\\\").replace("\"", "\\\"")
        val turnUsername = (System.getenv("ELAM_TURN_USERNAME") ?: "").replace("\\", "\\\\").replace("\"", "\\\"")
        val turnPassword = (System.getenv("ELAM_TURN_PASSWORD") ?: "").replace("\\", "\\\\").replace("\"", "\\\"")
        buildConfigField("String", "TURN_URL", "\"$turnUrl\"")
        buildConfigField("String", "TURN_USERNAME", "\"$turnUsername\"")
        buildConfigField("String", "TURN_PASSWORD", "\"$turnPassword\"")
    }
    buildFeatures { compose = true; buildConfig = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("io.coil-kt:coil-compose:2.7.0")
    implementation("io.github.webrtc-sdk:android:150.7871.04")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    debugImplementation("androidx.compose.ui:ui-tooling")
}
