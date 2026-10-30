plugins {
    alias(libs.plugins.android.library)
}
android {
    namespace = "dev.buddy.pairingnative"
    compileSdk { version = release(37) { minorApiLevel = 2 } }
    buildToolsVersion = "37.0.0"
    defaultConfig {
        minSdk = 33
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    lint {
        abortOnError = true
        warningsAsErrors = true
    }
    ndkVersion = "30.0.16248370"
    defaultConfig { ndk { abiFilters += listOf("arm64-v8a", "x86_64") } }
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "4.1.2"
        }
    }
}
dependencies {
    androidTestImplementation(libs.test.runner)
    androidTestImplementation(libs.test.junit)
    androidTestImplementation(libs.junit)
    testImplementation(libs.junit)
}

tasks.withType<Test>().configureEach {
    jvmArgs("--enable-native-access=ALL-UNNAMED")
    systemProperty("java.library.path", file("build/host").absolutePath)
}

android.sourceSets.getByName("androidTest").kotlin.srcDir("src/test/kotlin")
