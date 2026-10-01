// Scrobbling: hands played tracks to the user's scrobbler app (SLS broadcast).
plugins {
    alias(libs.plugins.tempobox.android.library)
    alias(libs.plugins.tempobox.hilt)
}

android {
    namespace = "com.tempobox.scrobble"
}

dependencies {
    api(projects.core.model)
    implementation(projects.core.common)
    implementation(projects.core.settings)

    implementation(libs.kotlinx.coroutines.android)
}
