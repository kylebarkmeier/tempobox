// Library management: scanning, watching, repositories, and file operations.
// The only module (besides core:tags) that touches audio files on disk.
plugins {
    alias(libs.plugins.tempobox.android.library)
    alias(libs.plugins.tempobox.hilt)
}

android {
    namespace = "com.tempobox.library"
}

dependencies {
    api(projects.core.model)
    api(projects.core.database)
    implementation(projects.core.common)
    implementation(projects.core.settings)
    implementation(projects.core.tags)
    implementation(projects.core.playlist)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.core.ktx)

    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
}
