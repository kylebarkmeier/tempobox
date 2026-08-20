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

dependencies {
    api(libs.kotlinx.serialization.json) // SmartRule trees serialize to JSON

    testImplementation(libs.junit)
    testImplementation(libs.truth)
}
