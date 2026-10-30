plugins {
    alias(libs.plugins.ksp)
    alias(libs.plugins.android.library)
    alias(libs.plugins.serialization)
}
android {
    namespace = "dev.buddy.storage"
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
}
dependencies {
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)
    testImplementation(libs.junit)
    implementation(project(":core"))
    implementation(libs.coroutines.android)
    implementation(libs.serialization.json)
}

ksp { arg("room.schemaLocation", "$projectDir/schemas") }
