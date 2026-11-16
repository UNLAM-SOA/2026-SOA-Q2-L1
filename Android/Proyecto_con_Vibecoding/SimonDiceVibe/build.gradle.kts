// Top-level build file. AGP 9 ships Kotlin built in, so only the Compose compiler plugin is added.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
}
