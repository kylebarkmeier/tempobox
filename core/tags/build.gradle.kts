// Tag IO: the only module allowed to read/write audio file metadata.
// Wraps jaudiotagger (ID3v1/v2, Vorbis comments, MP4 atoms, FLAC).
plugins {
    alias(libs.plugins.tempobox.android.library)
    alias(libs.plugins.tempobox.hilt)
}

android {
    namespace = "com.tempobox.tags"
}

dependencies {
    api(projects.core.model)
    implementation(projects.core.common)

    implementation(libs.jaudiotagger)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
}
