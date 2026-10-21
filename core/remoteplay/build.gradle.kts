// Network play: SSDP discovery, UPnP AV control of DLNA renderers, DLNA server
// (ContentDirectory) browsing, and the HTTP server that streams queue tracks
// to renderers. See docs/ARCHITECTURE.md "Network play".
plugins {
    alias(libs.plugins.tempobox.android.library)
    alias(libs.plugins.tempobox.hilt)
}

android {
    namespace = "com.tempobox.remoteplay"
}

dependencies {
    api(projects.core.model)
    implementation(projects.core.common)

    implementation(libs.okhttp)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.okhttp.mockwebserver)
}
