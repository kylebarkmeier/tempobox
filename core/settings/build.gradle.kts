// User preferences: a typed SettingsRepository over Preferences DataStore.
// Every user-configurable option in the app flows through this module.
plugins {
    alias(libs.plugins.tempobox.android.library)
    alias(libs.plugins.tempobox.hilt)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.tempobox.settings"
}

dependencies {
    api(projects.core.model)
    implementation(projects.core.common)

    api(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
}
