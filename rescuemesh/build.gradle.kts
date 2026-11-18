// Root build file. Plugin versions live in gradle/libs.versions.toml.
// AGP 9 has built-in Kotlin: do NOT apply org.jetbrains.kotlin.android anywhere.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
}
