// Pure Kotlin module: small shared utilities (formatting, coroutine plumbing).
plugins {
    alias(libs.plugins.kotlin.jvm)
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
    api(libs.kotlinx.coroutines.core)
    api(libs.javax.inject)

    testImplementation(libs.junit)
    testImplementation(libs.truth)
    testImplementation(libs.kotlinx.coroutines.test)
}
