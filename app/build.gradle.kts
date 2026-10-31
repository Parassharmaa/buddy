plugins {
    alias(libs.plugins.android.app)
    alias(libs.plugins.compose)
    alias(libs.plugins.serialization)
}
android {
    namespace = "dev.buddy"
    compileSdk { version = release(37) { minorApiLevel = 2 } }
    buildToolsVersion = "37.0.0"
    ndkVersion = "30.0.16248370"
    defaultConfig {
        minSdk = 33
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        applicationId = "dev.buddy"
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0-dev"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    testOptions { unitTests.isIncludeAndroidResources = true }
    lint {
        abortOnError = true
        warningsAsErrors = true
    }
    buildFeatures {
        buildConfig = true
        compose = true
        aidl = true
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
        }
    }
    providers.environmentVariable("BUDDY_DEBUG_KEYSTORE").orNull?.let { path ->
        signingConfigs.getByName("debug").storeFile = file(path)
        signingConfigs.getByName("debug").storeType = "PKCS12"
    }
}
val robolectricSdk = configurations.create("robolectricSdk") {
    isCanBeConsumed = false
    isTransitive = false
}
val prepareRobolectricSdk = tasks.register<Sync>("prepareRobolectricSdk") {
    from(robolectricSdk)
    into(layout.buildDirectory.dir("robolectric-sdk"))
}
dependencies {
    add(robolectricSdk.name, libs.robolectricSdk)
    compileOnly(project(":platform-api"))
    implementation(libs.work.runtime)
    implementation(project(":browser"))
    androidTestImplementation(libs.test.runner)
    androidTestImplementation(libs.test.uiautomator)
    androidTestImplementation(libs.test.junit)
    androidTestImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.test.core)
    testImplementation(libs.test.junit)
    testImplementation(platform(libs.compose.bom))
    testImplementation(libs.compose.test.junit4)
    debugImplementation(libs.compose.test.manifest)
    implementation(libs.adaptive)
    implementation(libs.adaptive.navigation)
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

tasks.withType<Test>().configureEach {
    dependsOn(prepareRobolectricSdk)
    testLogging.exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    // Robolectric's Android FD adapter needs this JDK 27 test-only export.
    jvmArgs("--add-exports=java.base/jdk.internal.access=ALL-UNNAMED", "--enable-native-access=ALL-UNNAMED")
    maxHeapSize = "2g"
    systemProperty("buddy.capture.dir", rootProject.file("captures").absolutePath)
    systemProperty("robolectric.offline", "true")
    systemProperty("robolectric.dependency.dir", layout.buildDirectory.dir("robolectric-sdk").get().asFile.absolutePath)
    listOf(
        "http.proxyHost",
        "http.proxyPort",
        "https.proxyHost",
        "https.proxyPort",
        "javax.net.ssl.trustStore"
    ).forEach { key ->
        System.getProperty(key)?.let { systemProperty(key, it) }
    }
}
