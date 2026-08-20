// Artwork: embedded album art loading (Coil fetcher) + Discogs artist images.
plugins {
    alias(libs.plugins.tempobox.android.library)
    alias(libs.plugins.tempobox.hilt)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.tempobox.artwork"
}

dependencies {
    api(projects.core.model)
    implementation(projects.core.common)
    implementation(projects.core.settings)
    implementation(projects.core.tags)

    api(libs.coil)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
}
