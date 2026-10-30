import java.util.Properties

plugins { `java-library` }

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}
val sdkRoot = providers.environmentVariable("ANDROID_HOME")
    .orElse(providers.environmentVariable("ANDROID_SDK_ROOT"))
    .orElse(
        providers.provider {
            val properties = Properties()
            rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use(properties::load)
            requireNotNull(properties.getProperty("sdk.dir")) { "Configure the Android SDK directory" }
        }
    )
dependencies {
    compileOnly(files(sdkRoot.map { "$it/platforms/android-37.2/android.jar" }))
}
