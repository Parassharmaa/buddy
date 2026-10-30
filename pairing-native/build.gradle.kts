plugins {
    alias(libs.plugins.android.library)
}
android {
    namespace = "dev.buddy.pairingnative"
    compileSdkVersion("android-37.2")
    buildToolsVersion = "37.0.0"
    defaultConfig {
        minSdk = 33
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    lint { abortOnError = true; warningsAsErrors = true }
    ndkVersion = "30.0.16248370"
    defaultConfig { ndk { abiFilters += listOf("arm64-v8a", "x86_64") } }
    externalNativeBuild { cmake { path = file("src/main/cpp/CMakeLists.txt"); version = "4.1.2" } }
}
dependencies {
    testImplementation(libs.junit)
    implementation("androidx.annotation:annotation:1.9.1")
}
