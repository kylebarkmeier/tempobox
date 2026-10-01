plugins {
    `kotlin-dsl`
}

group = "com.tempobox.buildlogic"

// The convention plugins compile against the AGP/Kotlin plugin APIs.
// The KSP and Compose-compiler plugins are only ever applied by id (never
// imported), so they are not needed here — and KSP >= 2.3 is compiled with a
// Kotlin metadata version newer than Gradle's embedded Kotlin can read.
dependencies {
    compileOnly(libs.android.gradle.plugin)
    compileOnly(libs.kotlin.gradle.plugin)
}

gradlePlugin {
    plugins {
        register("androidApplication") {
            id = "tempobox.android.application"
            implementationClass = "AndroidApplicationConventionPlugin"
        }
        register("androidLibrary") {
            id = "tempobox.android.library"
            implementationClass = "AndroidLibraryConventionPlugin"
        }
        register("androidCompose") {
            id = "tempobox.android.compose"
            implementationClass = "AndroidComposeConventionPlugin"
        }
        register("hilt") {
            id = "tempobox.hilt"
            implementationClass = "HiltConventionPlugin"
        }
    }
}
