// The TempoBox application: all Compose UI, navigation, widget, DI wiring.
plugins {
    alias(libs.plugins.tempobox.android.application)
    alias(libs.plugins.tempobox.android.compose)
    alias(libs.plugins.tempobox.hilt)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.tempobox"

    defaultConfig {
        applicationId = "com.tempobox"
        versionCode = 1
        versionName = "1.0.0"

        // Hilt-aware instrumentation runner (see src/androidTest).
        testInstrumentationRunner = "com.tempobox.HiltTestRunner"
        vectorDrawables.useSupportLibrary = true
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
        debug {
            applicationIdSuffix = ".debug"
        }
    }
}

dependencies {
    // Feature/core modules
    implementation(projects.core.model)
    implementation(projects.core.common)
    implementation(projects.core.database)
    implementation(projects.core.settings)
    implementation(projects.core.tags)
    implementation(projects.core.playlist)
    implementation(projects.core.library)
    implementation(projects.core.playback)
    implementation(projects.core.scrobble)
    implementation(projects.core.artwork)

    // AndroidX
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.hilt.navigation.compose)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.palette)

    // Widget
    implementation(libs.androidx.glance.appwidget)
    implementation(libs.androidx.glance.material3)

    // Media session (mini-player state comes through PlayerConnection)
    implementation(libs.androidx.media3.session)

    // Images
    implementation(libs.coil.compose)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    // Unit tests (JVM)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)

    // Instrumented tests (device/emulator)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.rules)
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.hilt.android.testing)
    kspAndroidTest(libs.hilt.compiler)
    androidTestImplementation(libs.androidx.uiautomator)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
