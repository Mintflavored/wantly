// Top-level build file where you can add configuration options common to all sub-projects/modules.
//
// AGP 9.2.1 поставляется со встроенным Kotlin 2.2.10. Повышаем его до 2.3.21
// (максимальная версия, под которую выпущен KSP) через buildscript classpath —
// это официальный способ для AGP 9 (см. AGP 9.0 release notes, "Upgrade to a higher KGP version").
buildscript {
    dependencies {
        classpath(libs.kotlin.gradle.plugin)
        classpath(libs.ksp.gradle.plugin)
    }
}

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
}
