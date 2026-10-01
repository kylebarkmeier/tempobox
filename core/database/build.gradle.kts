// Room persistence layer: entities, DAOs, database, and their Hilt bindings.
plugins {
    alias(libs.plugins.tempobox.android.library)
    alias(libs.plugins.tempobox.hilt)
}

android {
    namespace = "com.tempobox.database"
}

dependencies {
    api(projects.core.model)
    implementation(projects.core.common)

    // room-ktx merged into room-runtime in Room 2.7 (the ktx artifact is now empty).
    api(libs.androidx.room.runtime)
    ksp(libs.androidx.room.compiler)

    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.room.testing)
}
