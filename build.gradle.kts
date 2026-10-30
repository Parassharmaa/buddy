plugins {
    alias(libs.plugins.android.app) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.compose) apply false
    alias(libs.plugins.serialization) apply false
}
val ktlint = configurations.create("ktlint") {
    attributes { attribute(Bundling.BUNDLING_ATTRIBUTE, objects.named(Bundling.SHADOWED)) }
}
dependencies { add(ktlint.name, libs.ktlint) }
tasks.register<JavaExec>("formatKotlin") {
    classpath = ktlint
    mainClass.set("com.pinterest.ktlint.Main")
    args("--format", "**/src/**/*.kt", "**/*.gradle.kts", "!**/build/**", "!pairing-native/**")
}
tasks.register<JavaExec>("lintKotlin") {
    classpath = ktlint
    mainClass.set("com.pinterest.ktlint.Main")
    args("**/src/**/*.kt", "**/*.gradle.kts", "!**/build/**", "!pairing-native/**")
}
