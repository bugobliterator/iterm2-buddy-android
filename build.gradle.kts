plugins {
    alias(libs.plugins.android.application) apply false
    // AGP 9 compiles Kotlin itself. This only pins the Kotlin Gradle plugin version
    // AGP's built-in Kotlin uses; never apply it in a module.
    alias(libs.plugins.kotlin.version.pin) apply false
    alias(libs.plugins.kotlin.compose) apply false
}
