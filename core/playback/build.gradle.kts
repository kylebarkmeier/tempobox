// Playback engine: Media3 service + session, queue, shuffle, Bluetooth glue.
plugins {
    alias(libs.plugins.tempobox.android.library)
    alias(libs.plugins.tempobox.hilt)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.tempobox.playback"
}

dependencies {
    api(projects.core.model)
    implementation(projects.core.common)
    implementation(projects.core.settings)
    implementation(projects.core.library)
    implementation(projects.core.tags)
    implementation(projects.core.scrobble)

    api(libs.androidx.media3.exoplayer)
    api(libs.androidx.media3.session)
    api(libs.androidx.media3.common)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.guava)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.media3.test.utils)
}
