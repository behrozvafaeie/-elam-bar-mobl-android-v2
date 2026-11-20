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
        versionCode = 29
        versionName = "1.5.3"
        // Do not embed long-lived TURN credentials in an APK. A future call release
        // must obtain short-lived TURN credentials from an authenticated server endpoint.
        buildConfigField("String", "TURN_URL", "\"\"")
        buildConfigField("String", "TURN_USERNAME", "\"\"")
        buildConfigField("String", "TURN_PASSWORD", "\"\"")
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
