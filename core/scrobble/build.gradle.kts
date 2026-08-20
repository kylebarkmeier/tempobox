// Last.fm scrobbling: signed API client + durable offline scrobble queue.
plugins {
    alias(libs.plugins.tempobox.android.library)
    alias(libs.plugins.tempobox.hilt)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.tempobox.scrobble"
}

dependencies {
    api(projects.core.model)
    implementation(projects.core.common)
    implementation(projects.core.settings)

    implementation(libs.okhttp)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
}
