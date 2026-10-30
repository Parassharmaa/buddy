plugins {
    alias(libs.plugins.android.app)
    alias(libs.plugins.compose)
    alias(libs.plugins.serialization)
}
android {
    namespace = "dev.buddy.app"
    compileSdkVersion("android-37.2")
    buildToolsVersion = "37.0.0"
    defaultConfig {
        minSdk = 33
        applicationId = "dev.buddy"
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0-dev"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    lint {
        abortOnError = true
        warningsAsErrors = true
    }
    buildFeatures { compose = true }
}
dependencies {
    testImplementation(libs.junit)
    implementation(project(":core"))
    implementation(project(":adb"))
    implementation(project(":shell-api"))
    implementation(project(":shell-helper"))
    implementation(project(":phone"))
    implementation(project(":storage"))
    implementation(project(":designsystem"))
    implementation(libs.coroutines.android)
    implementation(libs.serialization.json)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.preview)
    debugImplementation(libs.compose.tooling)
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.compose)
    implementation(libs.lifecycle.viewmodel)
}
