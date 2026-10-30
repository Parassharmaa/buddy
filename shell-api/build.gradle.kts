plugins {
    alias(libs.plugins.android.library)
}
android {
    namespace = "dev.buddy.shellapi"
    compileSdkVersion("android-37.2")
    buildToolsVersion = "37.0.0"
    defaultConfig {
        minSdk = 33
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    lint {
        abortOnError = true
        warningsAsErrors = true
    }
    buildFeatures { aidl = true }
}
dependencies {
    testImplementation(libs.junit)
}
