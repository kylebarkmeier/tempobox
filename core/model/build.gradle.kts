// Pure Kotlin module: domain models shared by every other module.
// Deliberately has NO Android dependencies so models stay trivially unit-testable.
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}
kotlin {
    // Match the Android modules' JVM target regardless of the Gradle JDK
    // (Android Studio bundles JBR 21; without this Kotlin would target 21
    // while javac targets 17 and the build fails the consistency check).
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    api(libs.kotlinx.serialization.json) // SmartRule trees serialize to JSON

    testImplementation(libs.junit)
    testImplementation(libs.truth)
}
